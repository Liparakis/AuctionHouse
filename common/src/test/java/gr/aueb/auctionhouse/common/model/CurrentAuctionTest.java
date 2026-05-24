package gr.aueb.auctionhouse.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentAuctionTest {

  @Test
  void computesTenPercentBidCapBeforeLateWindow() {
    CurrentAuction auction = new CurrentAuction(
        "obj-1",
        "seller-token",
        "item",
        100.0,
        100,
        1_000_000L
    );

    CurrentAuction.BidWindow window = auction.bidWindowAt(1_000_050L);

    assertEquals(100.0, window.minimumAcceptedBid());
    assertEquals(110.0, window.maximumAcceptedBid(), 0.0001);
    assertFalse(window.isLateWindow());
  }

  @Test
  void computesTwentyPercentBidCapInsideLateWindow() {
    CurrentAuction auction = new CurrentAuction(
        "obj-1",
        "seller-token",
        "item",
        100.0,
        100,
        1_000_000L
    );

    CurrentAuction.BidWindow window = auction.bidWindowAt(1_000_091L);

    assertEquals(100.0, window.minimumAcceptedBid());
    assertEquals(120.0, window.maximumAcceptedBid(), 0.0001);
    assertTrue(window.isLateWindow());
  }

  @Test
  void storesBidHistoryInDescendingOrder() {
    CurrentAuction auction = new CurrentAuction(
        "obj-1",
        "seller-token",
        "item",
        100.0,
        100,
        1_000_000L
    );

    assertTrue(auction.tryPlaceBid("bidder-1", 105.0, 1_000_010L));
    assertTrue(auction.tryPlaceBid("bidder-2", 108.0, 1_000_020L));

    assertEquals(2, auction.bidHistory().size());
    assertEquals("bidder-2", auction.bidHistory().get(0).bidderToken());
    assertEquals("bidder-1", auction.bidHistory().get(1).bidderToken());
  }
}
