package gr.aueb.auctionhouse.server.client;

import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.common.protocol.message.CommandMessage;
import gr.aueb.auctionhouse.server.auction.AuctionEngine;
import gr.aueb.auctionhouse.server.config.ServerMessages;
import gr.aueb.auctionhouse.server.state.ServerState;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProcessorTest {

  @Test
  void bidUsesSharedWrongArityMessage() {
    ServerState state = new ServerState();
    CommandProcessor processor = new CommandProcessor(state, new AuctionEngine(state));
    ClientSession session = authenticatedSession(state, "alice", "token-1");

    String response = processor.process(session, CommandMessage.parse("BID|obj-1"));

    assertEquals("ERR|BAD_REQUEST|" + ServerMessages.MSG_BID_REQUIRES_1_ARG, response);
  }

  @Test
  void getDetailsUsesSharedWrongArityMessage() {
    ServerState state = new ServerState();
    CommandProcessor processor = new CommandProcessor(state, new AuctionEngine(state));
    ClientSession session = authenticatedSession(state, "alice", "token-1");

    String response = processor.process(session, CommandMessage.parse("GET_DETAILS"));

    assertEquals("ERR|BAD_REQUEST|" + ServerMessages.MSG_GET_DETAILS_REQUIRES_0_ARGS, response);
  }

  @Test
  void bidUsesSharedNoAuctionMessageWhenNoAuctionsExist() {
    ServerState state = new ServerState();
    CommandProcessor processor = new CommandProcessor(state, new AuctionEngine(state));
    ClientSession session = authenticatedSession(state, "alice", "token-1");

    String response = processor.process(session, CommandMessage.parse("BID|obj-1|10.5"));

    assertEquals("ERR|NO_AUCTION|" + ServerMessages.MSG_NO_ACTIVE_AUCTION, response);
  }

  @Test
  void bidUsesSharedTooLowMessagePrefixForOutOfWindowBid() {
    ServerState state = new ServerState();
    CommandProcessor processor = new CommandProcessor(state, new AuctionEngine(state));
    ClientSession session = authenticatedSession(state, "alice", "token-1");
    state.activeAuctions().put("obj-1",
        new CurrentAuction("obj-1", "seller-token", "item", 100.0, 60));

    String response = processor.process(session, CommandMessage.parse("BID|obj-1|150.0"));

    String prefix = "ERR|BID_TOO_LOW|" + ServerMessages.MSG_BID_TOO_LOW;
    assertTrue(response.startsWith(prefix));
  }

  private static ClientSession authenticatedSession(ServerState state, String username, String token) {
    ClientSession session = new ClientSession(new Socket(), new PrintWriter(new StringWriter(), true));
    session.setToken(token);
    state.sessions().put(token,
        new ServerState.SessionInfo(username, System.currentTimeMillis() + 60_000));
    return session;
  }
}
