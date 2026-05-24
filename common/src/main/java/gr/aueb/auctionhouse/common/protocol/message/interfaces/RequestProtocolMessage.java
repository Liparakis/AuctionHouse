package gr.aueb.auctionhouse.common.protocol.message.interfaces;

import gr.aueb.auctionhouse.common.protocol.enums.CommandType;

public interface RequestProtocolMessage extends ProtocolMessage {
    CommandType command();

    String[] args();

    String rawCommand();

    String formatResponse(String response);
}
