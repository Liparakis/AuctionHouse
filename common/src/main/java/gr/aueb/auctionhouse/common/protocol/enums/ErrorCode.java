package gr.aueb.auctionhouse.common.protocol.enums;

import gr.aueb.auctionhouse.common.protocol.codec.EnumWireParser;

public enum ErrorCode {
    BAD_REQUEST,
    BAD_COMMAND,
    USER_EXISTS,
    AUTH_FAILED,
    NOT_LOGGED_IN,
    NOT_IMPLEMENTED,
    NO_AUCTION,
    INVALID_OBJECT,
    AUCTION_CLOSED,
    BID_TOO_LOW,
    INTERNAL_ERROR,
    UNKNOWN;

    public static ErrorCode fromWire(String value) {
        return EnumWireParser.parse(value, ErrorCode.class, UNKNOWN);
    }
}

