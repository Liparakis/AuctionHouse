package gr.aueb.auctionhouse.common.protocol.enums;

import gr.aueb.auctionhouse.common.protocol.codec.EnumWireParser;

public enum OkCode {
    CONNECTED,
    PONG,
    WELCOME,
    COMMANDS,
    REGISTERED,
    TOKEN,
    LOGGED_OUT,
    AUCTION_ENQUEUED,
    PEER_REGISTERED,
    NO_AUCTION,
    ACTIVE_AUCTIONS,
    CURRENT_AUCTION,
    BID_ACCEPTED,
    USER_STATS,
    TRANSACTION_RECORDED,
    UNKNOWN;

    public static OkCode fromWire(String value) {
        return EnumWireParser.parse(value, OkCode.class, UNKNOWN);
    }
}

