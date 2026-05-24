package gr.aueb.auctionhouse.common.protocol.message.interfaces;

import gr.aueb.auctionhouse.common.protocol.enums.ErrorCode;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;

public interface ResponseProtocolMessage extends ProtocolMessage {
    ResponseKind kind();

    OkCode okCode();

    ErrorCode errorCode();

    EventType eventType();

    String[] fields();
}
