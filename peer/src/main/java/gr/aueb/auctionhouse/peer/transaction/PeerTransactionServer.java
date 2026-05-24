package gr.aueb.auctionhouse.peer.transaction;

import gr.aueb.auctionhouse.peer.transaction.enums.TxnWireType;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PeerTransactionServer implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(PeerTransactionServer.class.getName());

  private static final int ACCEPT_TIMEOUT_MS = 1_000;
  private static final int ACK_TIMEOUT_MS = 2_000;
  private static final int MAX_DATAGRAM_BYTES = 2048;
  private static final int CHUNK_SIZE_BYTES = 64;
  private static final int WINDOW_SIZE = 3;

  private final int port;
  private final String ownerToken;
  private final Path sharedRoot;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private volatile DatagramSocket socket;
  private volatile int boundPort;
  private Thread receiveThread;

  public PeerTransactionServer(int port, String ownerToken, Path sharedRoot) {
    this.port = port;
    this.ownerToken = ownerToken;
    this.sharedRoot = sharedRoot;
  }

  public void start() throws IOException {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    socket = new DatagramSocket(port);
    socket.setSoTimeout(ACCEPT_TIMEOUT_MS);
    boundPort = socket.getLocalPort();

    receiveThread = new Thread(this::receiveLoop, "txn-udp-server-" + boundPort);
    receiveThread.setDaemon(true);
    receiveThread.start();

    LOG.info("[PEER-TXN] UDP listening on port " + boundPort);
  }

  public int listenPort() {
    return boundPort;
  }

  private void receiveLoop() {
    while (running.get()) {
      try {
        DatagramPacket packet = receivePacket(ACCEPT_TIMEOUT_MS);
        TxnWireMessage message = parse(packet);
        if (message.type() == TxnWireType.TXN_HELLO) {
          processTransaction(packet, message);
        }
      } catch (SocketTimeoutException ignored) {
      } catch (Exception ex) {
        if (running.get()) {
          LOG.log(Level.WARNING, "[PEER-TXN] UDP receive loop failed", ex);
        }
      }
    }
  }

  private void processTransaction(DatagramPacket helloPacket, TxnWireMessage hello)
      throws Exception {
    String objectId = hello.objectId();
    InetSocketAddress buyerAddress = new InetSocketAddress(
        helloPacket.getAddress(), helloPacket.getPort());
    send(buyerAddress, TxnWireMessage.ack(objectId, ownerToken));

    Path metadataPath = ObjectMetadataStore.metadataPath(sharedRoot, objectId);
    if (!Files.exists(metadataPath)) {
      send(buyerAddress, TxnWireMessage.err("MISSING_METADATA"));
      return;
    }

    List<String> packets = packetize(Files.readAllBytes(metadataPath), objectId);
    if (!sendGoBackN(buyerAddress, objectId, packets)) {
      return;
    }

    TxnWireMessage done = receiveMatchingDone(objectId, buyerAddress);
    if (done.type() != TxnWireType.TXN_DONE) {
      return;
    }
    send(buyerAddress, TxnWireMessage.doneAck(objectId));
    Files.deleteIfExists(metadataPath);
    LOG.info("[PEER-TXN] Completed UDP transaction - object=" + objectId);
  }

  private boolean sendGoBackN(InetSocketAddress buyerAddress, String objectId, List<String> packets)
      throws Exception {
    int base = 0;
    int nextSeq = 0;

    while (base < packets.size()) {
      while (nextSeq < packets.size() && nextSeq < base + WINDOW_SIZE) {
        LOG.info("[PEER-TXN][GBN] SEND object=" + objectId + " seq=" + nextSeq + " base=" + base);
        send(buyerAddress, packets.get(nextSeq));
        nextSeq++;
      }

      try {
        DatagramPacket ackPacket = receivePacket(ACK_TIMEOUT_MS);
        TxnWireMessage ack = parse(ackPacket);
        if (ack.type() == TxnWireType.TXN_PACKET_ACK && objectId.equals(ack.objectId())) {
          LOG.info("[PEER-TXN][GBN] ACK object=" + objectId + " ack=" + ack.ackSequenceNumber()
              + " oldBase=" + base);
          base = Math.max(base, ack.ackSequenceNumber() + 1);
        }
      } catch (SocketTimeoutException ex) {
        LOG.warning("[PEER-TXN][GBN] TIMEOUT object=" + objectId + " base=" + base
            + " nextSeq=" + nextSeq + " retransmit_from=" + base);
        nextSeq = base;
      }
    }
    return true;
  }

  private TxnWireMessage receiveMatchingDone(String objectId, InetSocketAddress buyerAddress)
      throws Exception {
    while (running.get()) {
      DatagramPacket packet = receivePacket(ACK_TIMEOUT_MS);
      if (!buyerAddress.getAddress().equals(packet.getAddress())
          || buyerAddress.getPort() != packet.getPort()) {
        continue;
      }
      TxnWireMessage message = parse(packet);
      if (message.type() == TxnWireType.TXN_DONE && objectId.equals(message.objectId())) {
        return message;
      }
    }
    return TxnWireMessage.parse(null);
  }

  private List<String> packetize(byte[] bytes, String objectId) {
    List<String> packets = new ArrayList<>();
    int totalPackets = (int) Math.ceil(bytes.length / (double) CHUNK_SIZE_BYTES);
    for (int seq = 0; seq < totalPackets; seq++) {
      int start = seq * CHUNK_SIZE_BYTES;
      int end = Math.min(bytes.length, start + CHUNK_SIZE_BYTES);
      byte[] chunk = java.util.Arrays.copyOfRange(bytes, start, end);
      packets.add(TxnWireMessage.data(objectId, seq, totalPackets,
          Base64.getEncoder().encodeToString(chunk)));
    }
    return packets;
  }

  private DatagramPacket receivePacket(int timeoutMs) throws IOException {
    socket.setSoTimeout(timeoutMs);
    byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    return packet;
  }

  private TxnWireMessage parse(DatagramPacket packet) {
    String line = new String(packet.getData(), packet.getOffset(), packet.getLength(),
        StandardCharsets.UTF_8);
    return TxnWireMessage.parse(line);
  }

  private void send(InetSocketAddress address, String line) throws IOException {
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(bytes, bytes.length, address));
  }

  @Override
  public void close() {
    running.set(false);
    DatagramSocket current = socket;
    if (current != null && !current.isClosed()) {
      current.close();
    }
    if (receiveThread != null) {
      receiveThread.interrupt();
    }
  }
}
