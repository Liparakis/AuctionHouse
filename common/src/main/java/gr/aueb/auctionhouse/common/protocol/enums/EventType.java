package gr.aueb.auctionhouse.common.protocol.enums;

import gr.aueb.auctionhouse.common.protocol.codec.EnumWireParser;

public enum EventType {
    AUCTION_QUEUED,
    AUCTION_STARTED,
    BID_ACCEPTED,
    AUCTION_ENDED,
    AUCTION_CANCELLED,
    TRANSACTION_READY,
    TRANSACTION_PROMOTED,
    TRANSACTION_COMPLETED,
    TRANSACTION_FAILED,
    UNKNOWN;

    public static EventType fromWire(String value) {
        return EnumWireParser.parse(value, EventType.class, UNKNOWN);
    }
}

