package gr.aueb.auctionhouse.common.protocol.enums;

import gr.aueb.auctionhouse.common.protocol.codec.EnumWireParser;

public enum CommandType {
    PING,
    HELLO,
    REGISTER,
    LOGIN,
    LOGOUT,
    SEND_AUCTION_REQUEST,
    PEER_LISTEN,
    GET_CURRENT_AUCTION,
    BID,
    GET_DETAILS,
    GET_USER_STATS,
    TRANSACTION_COMPLETE,
    TRANSACTION_FAILED,
    HELP,
    UNKNOWN;

    public static CommandType fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase()) {
            case "requestauction", "getauctionrequest" -> SEND_AUCTION_REQUEST;
            case "getcurrentauction", "sendcurrentauction" -> GET_CURRENT_AUCTION;
            case "getauctiondetails", "sendauctiondetails" -> GET_DETAILS;
            case "placebid" -> BID;
            case "transaction" -> TRANSACTION_COMPLETE;
            default -> EnumWireParser.parse(value, CommandType.class, UNKNOWN);
        };
    }
}

