package gr.aueb.auctionhouse.peer.transaction.enums;

import java.util.Locale;

// txn message names
public enum TxnWireType {

    // buyer says hi
    TXN_HELLO,

    // seller says ok
    TXN_ACK,

    // buyer says done
    TXN_DONE,

    // seller sends meta
    TXN_META,

    // seller sends one GBN data packet
    TXN_DATA,

    // buyer sends cumulative ack for data packets
    TXN_PACKET_ACK,

    // buyer got meta
    TXN_META_ACK,

    // seller says all done
    TXN_DONE_ACK,

    // error message
    TXN_ERR,

    // unknown value
    UNKNOWN;

    // turns text into type
    public static TxnWireType fromWire(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return TxnWireType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
