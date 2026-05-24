package gr.aueb.auctionhouse.peer.transaction;

import gr.aueb.auctionhouse.peer.transaction.enums.TxnWireType;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PeerTransactionClient {

  private static final Logger LOG = Logger.getLogger(PeerTransactionClient.class.getName());

  private static final int SOCKET_TIMEOUT_MS = 2_500;
  private static final int MAX_DATAGRAM_BYTES = 2048;
  private static final double INBOUND_DROP_PROBABILITY = 0.20;
  private static final double ACK_SEND_PROBABILITY = 0.80;

  private PeerTransactionClient() {
  }

  public static boolean execute(String objectId, String sellerIp, int sellerPort, String token,
      Path sharedRoot) {
    if (sellerPort <= 0) {
      LOG.warning("[PEER-TXN] Invalid seller port: " + sellerPort);
      return false;
    }
    Random random = new Random();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(SOCKET_TIMEOUT_MS);
      InetSocketAddress sellerAddress = new InetSocketAddress(sellerIp, sellerPort);

      send(socket, sellerAddress, TxnWireMessage.hello(objectId, token));
      if (!expectAck(socket, objectId)) {
        return false;
      }

      byte[] metadataBytes = receiveMetadata(socket, sellerAddress, objectId, random);
      ObjectMetadataStore.writeMetadata(sharedRoot, objectId, metadataBytes);

      send(socket, sellerAddress, TxnWireMessage.done(objectId, token));
      if (!expectDoneAck(socket, objectId)) {
        return false;
      }

      LOG.info("[PEER-TXN] Winner completed transaction for object=" + objectId);
      return true;
    } catch (Exception ex) {
      LOG.log(Level.WARNING, "[PEER-TXN] Winner UDP transaction failed for object=" + objectId, ex);
      return false;
    }
  }

  private static boolean expectAck(DatagramSocket socket, String objectId) throws Exception {
    TxnWireMessage ack = receiveMessage(socket);
    return ack.type() == TxnWireType.TXN_ACK && objectId.equals(ack.objectId());
  }

  private static byte[] receiveMetadata(DatagramSocket socket, InetSocketAddress sellerAddress,
      String objectId, Random random) throws Exception {
    TreeMap<Integer, byte[]> received = new TreeMap<>();
    int expectedSeq = 0;
    int totalPackets;

    while (true) {
      TxnWireMessage message = receiveMessage(socket);
      if (message.type() != TxnWireType.TXN_DATA || !objectId.equals(message.objectId())) {
        continue;
      }
      if (random.nextDouble() < INBOUND_DROP_PROBABILITY) {
        continue;
      }

      totalPackets = message.totalPackets();
      if (message.sequenceNumber() == expectedSeq) {
        received.put(expectedSeq, decodeChunk(message.metaPayloadBase64()));
        expectedSeq++;
      }

      int cumulativeAck = expectedSeq - 1;
      if (cumulativeAck >= 0 && random.nextDouble() <= ACK_SEND_PROBABILITY) {
        send(socket, sellerAddress, TxnWireMessage.packetAck(objectId, cumulativeAck));
      }

      if (totalPackets > 0 && received.size() == totalPackets) {
        return assemble(received);
      }
    }
  }

  private static boolean expectDoneAck(DatagramSocket socket, String objectId) throws Exception {
    TxnWireMessage doneAck = receiveMessage(socket);
    return doneAck.type() == TxnWireType.TXN_DONE_ACK && objectId.equals(doneAck.objectId());
  }

  private static void send(DatagramSocket socket, InetSocketAddress address, String line)
      throws Exception {
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(bytes, bytes.length, address));
  }

  private static TxnWireMessage receiveMessage(DatagramSocket socket) throws Exception {
    byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    String line = new String(packet.getData(), packet.getOffset(), packet.getLength(),
        StandardCharsets.UTF_8);
    return TxnWireMessage.parse(line);
  }

  private static byte[] decodeChunk(String base64Payload) {
    return java.util.Base64.getDecoder().decode(base64Payload);
  }

  private static byte[] assemble(TreeMap<Integer, byte[]> chunks) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] chunk : chunks.values()) {
      out.write(chunk);
    }
    return out.toByteArray();
  }
}
