package gr.aueb.auctionhouse.peer.transaction;

import gr.aueb.auctionhouse.common.protocol.codec.Protocol;
import gr.aueb.auctionhouse.peer.transaction.enums.TxnWireType;

// parsed txn line
public record TxnWireMessage(TxnWireType type, String objectId, String principal, String errorCode,
                             long metaSizeBytes,
                             String metaChecksum, String metaPayloadBase64, int sequenceNumber,
                             int ackSequenceNumber, int totalPackets, String raw) {

  private static final TxnWireMessage UNKNOWN_MSG = new TxnWireMessage(TxnWireType.UNKNOWN, "", "",
      "", -1, "", "", -1, -1, -1, "");

  public static TxnWireMessage parse(String line) {
    if (line == null) {
      return UNKNOWN_MSG;
    }

    String[] parts = line.split("\\|", -1);
    TxnWireType type = parts.length > 0 ? TxnWireType.fromWire(parts[0]) : TxnWireType.UNKNOWN;

    return switch (type) {
      case TXN_HELLO, TXN_ACK, TXN_DONE -> withPrincipal(type, parts, line);
      case TXN_META -> withMeta(parts, line);
      case TXN_DATA -> withData(parts, line);
      case TXN_PACKET_ACK -> withPacketAck(parts, line);
      case TXN_META_ACK, TXN_DONE_ACK -> withObjectOnly(type, parts, line);
      case TXN_ERR -> withError(parts, line);
      case UNKNOWN -> new TxnWireMessage(TxnWireType.UNKNOWN, "", "", "", -1, "", "", -1, -1, -1,
          line);
    };
  }

  private static TxnWireMessage withPrincipal(TxnWireType type, String[] parts, String line) {
    return new TxnWireMessage(type, field(parts, 1), field(parts, 2), "", -1, "", "", -1, -1, -1,
        line);
  }

  private static TxnWireMessage withMeta(String[] parts, String line) {
    return new TxnWireMessage(TxnWireType.TXN_META, field(parts, 1), "", "", parseLong(parts, 2),
        field(parts, 3), field(parts, 4), -1, -1, -1, line);
  }

  private static TxnWireMessage withData(String[] parts, String line) {
    return new TxnWireMessage(TxnWireType.TXN_DATA, field(parts, 1), "", "", -1, "",
        field(parts, 4), parseInt(parts, 2), -1, parseInt(parts, 3), line);
  }

  private static TxnWireMessage withPacketAck(String[] parts, String line) {
    return new TxnWireMessage(TxnWireType.TXN_PACKET_ACK, field(parts, 1), "", "", -1, "", "",
        -1, parseInt(parts, 2), -1, line);
  }

  private static TxnWireMessage withObjectOnly(TxnWireType type, String[] parts, String line) {
    return new TxnWireMessage(type, field(parts, 1), "", "", -1, "", "", -1, -1, -1, line);
  }

  private static TxnWireMessage withError(String[] parts, String line) {
    return new TxnWireMessage(TxnWireType.TXN_ERR, "", "", field(parts, 1), -1, "", "", -1, -1,
        -1, line);
  }

  public static String hello(String objectId, String winnerToken) {
    return Protocol.join(TxnWireType.TXN_HELLO.name(), objectId, winnerToken);
  }

  public static String ack(String objectId, String sellerToken) {
    return Protocol.join(TxnWireType.TXN_ACK.name(), objectId, sellerToken);
  }

  public static String done(String objectId, String winnerToken) {
    return Protocol.join(TxnWireType.TXN_DONE.name(), objectId, winnerToken);
  }

  public static String data(String objectId, int sequenceNumber, int totalPackets,
      String base64Payload) {
    return Protocol.join(TxnWireType.TXN_DATA.name(), objectId, Integer.toString(sequenceNumber),
        Integer.toString(totalPackets), base64Payload);
  }

  public static String packetAck(String objectId, int ackSequenceNumber) {
    return Protocol.join(TxnWireType.TXN_PACKET_ACK.name(), objectId,
        Integer.toString(ackSequenceNumber));
  }

  public static String doneAck(String objectId) {
    return Protocol.join(TxnWireType.TXN_DONE_ACK.name(), objectId);
  }

  public static String err(String code) {
    return Protocol.join(TxnWireType.TXN_ERR.name(), code);
  }

  private static String field(String[] parts, int index) {
    return parts.length > index ? parts[index] : "";
  }

  @SuppressWarnings("SameParameterValue")
  private static long parseLong(String[] parts, int index) {
    if (parts.length <= index) {
      return -1;
    }
    try {
      return Long.parseLong(parts[index]);
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static int parseInt(String[] parts, int index) {
    if (parts.length <= index) {
      return -1;
    }
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }
}
