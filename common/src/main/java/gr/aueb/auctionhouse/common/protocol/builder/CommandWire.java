package gr.aueb.auctionhouse.common.protocol.builder;

import gr.aueb.auctionhouse.common.protocol.codec.Protocol;
import gr.aueb.auctionhouse.common.protocol.enums.CommandType;

public final class CommandWire {

    private CommandWire() {
    }

    public static String ping() {
        return CommandType.PING.name();
    }

    public static String register(String username, String password) {
        return join(CommandType.REGISTER, username, password);
    }

    public static String login(String username, String password) {
        return join(CommandType.LOGIN, username, password);
    }

    public static String requestAuction(
            String description,
            double startPrice,
            int durationSec
    ) {
        return join(CommandType.SEND_AUCTION_REQUEST,
                description == null ? "" : description,
                Double.toString(startPrice),
                Integer.toString(durationSec));
    }

    public static String peerListen(int port) {
        return join(CommandType.PEER_LISTEN, Integer.toString(port));
    }

    public static String peerListen(String listenIp, int port) {
        return join(CommandType.PEER_LISTEN,
                listenIp == null ? "" : listenIp,
                Integer.toString(port));
    }

    public static String getCurrentAuctions() {
        return CommandType.GET_CURRENT_AUCTION.name();
    }

  public static String getAuctionDetails(String objectId) {
        return join(CommandType.GET_DETAILS, objectId);
    }

    public static String placeBid(String objectId, double amount) {
        return join(CommandType.BID, objectId, Double.toString(amount));
    }

    public static String transaction(String objectId) {
        return join(CommandType.TRANSACTION_COMPLETE, objectId);
    }

    public static String transactionFailed(String objectId) {
        return join(CommandType.TRANSACTION_FAILED, objectId);
    }

    private static String join(CommandType type, String... fields) {
        String[] payload = new String[fields.length + 1];
        payload[0] = type.name();
        System.arraycopy(fields, 0, payload, 1, fields.length);
        return Protocol.join(payload);
    }
}
