package gr.aueb.auctionhouse.common.protocol.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandWireTest {

  @Test
  void buildsObjectScopedBidCommand() {
    assertEquals("BID|obj-77|120.5", CommandWire.placeBid("obj-77", 120.5));
  }

  @Test
  void buildsObjectScopedDetailsCommand() {
    assertEquals("GET_DETAILS|obj-77", CommandWire.getAuctionDetails("obj-77"));
  }
}
