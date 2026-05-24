package gr.aueb.auctionhouse.server.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerStateTest {

  @Test
  void successfulAuctionUpdatesBidderReputationWithBetaPointTwentyFive() {
    ServerState.UserStats stats = ServerState.UserStats.initial();

    ServerState.UserStats updated = stats.recordBidderSuccess();

    assertEquals(1, updated.numAuctionsAsBidder());
    assertEquals(1.0, updated.reputationScore(), 0.0001);
  }

  @Test
  void failedAwardedBidderLosesReputationWithBetaPointTwentyFive() {
    ServerState.UserStats stats = new ServerState.UserStats(0, 0, 1.0);

    ServerState.UserStats updated = stats.recordAwardFailure();

    assertEquals(0, updated.numAuctionsAsBidder());
    assertEquals(0.75, updated.reputationScore(), 0.0001);
  }
}
