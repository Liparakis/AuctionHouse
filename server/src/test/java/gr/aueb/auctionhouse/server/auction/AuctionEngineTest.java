package gr.aueb.auctionhouse.server.auction;

import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.server.client.ClientSession;
import gr.aueb.auctionhouse.server.state.ServerState;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionEngineTest {

  @Test
  void transactionSuccessUpdatesBidderStatsAndReputation() {
    ServerState state = new ServerState();
    AuctionEngine engine = new AuctionEngine(state);

    state.sessions().put("bid-token",
        new ServerState.SessionInfo("bidder", System.currentTimeMillis() + 60_000));
    state.userStats().put("bidder", ServerState.UserStats.initial());
    state.pendingTransactions().put("obj-1",
        new CurrentAuction("obj-1", "seller-token", "seller", "item", 10.0, 60));

    boolean updated = engine.recordTransactionSuccess("obj-1", "bid-token");

    assertTrue(updated);
    ServerState.UserStats stats = state.userStats().get("bidder");
    assertEquals(1, stats.numAuctionsAsBidder());
    assertEquals(1.0, stats.reputationScore(), 0.0001);
  }

  @Test
  void transactionFailureUpdatesBidderReputationDownward() {
    ServerState state = new ServerState();
    AuctionEngine engine = new AuctionEngine(state);

    state.sessions().put("bid-token",
        new ServerState.SessionInfo("bidder", System.currentTimeMillis() + 60_000));
    state.userStats().put("bidder", ServerState.UserStats.initial());
    CurrentAuction auction = new CurrentAuction("obj-1", "seller-token", "seller", "item", 10.0, 60);
    auction.tryPlaceBid("bid-token", "bidder", 10.5, System.currentTimeMillis() / 1000L);
    state.pendingTransactions().put("obj-1", auction);

    boolean updated = engine.recordTransactionFailure("obj-1", "bid-token");

    assertTrue(updated);
    ServerState.UserStats stats = state.userStats().get("bidder");
    assertEquals(0, stats.numAuctionsAsBidder());
    assertEquals(0.75, stats.reputationScore(), 0.0001);
  }

  @Test
  void finishAuctionUsesReconnectedBidderSessionForTransaction() throws Exception {
    ServerState state = new ServerState();
    AuctionEngine engine = new AuctionEngine(state);
    CapturedSession captured = new CapturedSession();

    state.connectedClients().put(captured.session.clientId(), captured.session);
    state.sessions().put("seller-token",
        new ServerState.SessionInfo("seller", System.currentTimeMillis() + 60_000));
    state.sessions().put("bid-token-old",
        new ServerState.SessionInfo("bidder", System.currentTimeMillis() + 60_000));
    state.activePeers().put("seller-token",
        new ServerState.PeerEndpoint("127.0.0.1", 6001, "seller"));

    CurrentAuction auction = new CurrentAuction("obj-1", "seller-token", "seller", "item", 10.0, 60);
    auction.tryPlaceBid("bid-token-old", "bidder", 11.0, System.currentTimeMillis() / 1000L);
    state.activeAuctions().put("obj-1", auction);

    state.sessions().remove("bid-token-old");
    state.activePeers().remove("bid-token-old");
    state.sessions().put("bid-token-new",
        new ServerState.SessionInfo("bidder", System.currentTimeMillis() + 60_000));
    state.activePeers().put("bid-token-new",
        new ServerState.PeerEndpoint("127.0.0.1", 6002, "bidder"));

    invokeFinishActiveAuction(engine, auction);

    assertTrue(waitForOutput(captured,
            "EVENT|TRANSACTION_READY|obj-1|seller-token|bid-token-new"),
        captured.output());
  }

  @Test
  void finishAuctionMarksEligibleWinnerAsAwardedUntilTransactionCompletes() throws Exception {
    ServerState state = new ServerState();
    AuctionEngine engine = new AuctionEngine(state);
    CapturedSession captured = new CapturedSession();

    state.connectedClients().put(captured.session.clientId(), captured.session);
    state.sessions().put("seller-token",
        new ServerState.SessionInfo("seller", System.currentTimeMillis() + 60_000));
    state.sessions().put("bid-token",
        new ServerState.SessionInfo("bidder", System.currentTimeMillis() + 60_000));
    state.activePeers().put("seller-token",
        new ServerState.PeerEndpoint("127.0.0.1", 6001, "seller"));
    state.activePeers().put("bid-token",
        new ServerState.PeerEndpoint("127.0.0.1", 6002, "bidder"));

    CurrentAuction auction = new CurrentAuction("obj-1", "seller-token", "seller", "item", 10.0, 60);
    auction.tryPlaceBid("bid-token", "bidder", 11.0, System.currentTimeMillis() / 1000L);
    state.activeAuctions().put("obj-1", auction);

    invokeFinishActiveAuction(engine, auction);

    assertTrue(waitForOutput(captured,
            "EVENT|AUCTION_ENDED|obj-1|seller-token|bid-token|11.0|AWARDED"),
        captured.output());
  }

  @Test
  void finishAuctionDoesNotMarkSoldWhenNoEligibleBidderExists() throws Exception {
    ServerState state = new ServerState();
    AuctionEngine engine = new AuctionEngine(state);
    CapturedSession captured = new CapturedSession();

    state.connectedClients().put(captured.session.clientId(), captured.session);
    state.sessions().put("seller-token",
        new ServerState.SessionInfo("seller", System.currentTimeMillis() + 60_000));
    state.sessions().put("bid-token-old",
        new ServerState.SessionInfo("bidder", System.currentTimeMillis() + 60_000));
    state.activePeers().put("seller-token",
        new ServerState.PeerEndpoint("127.0.0.1", 6001, "seller"));

    CurrentAuction auction = new CurrentAuction("obj-1", "seller-token", "seller", "item", 10.0, 60);
    auction.tryPlaceBid("bid-token-old", "bidder", 11.0, System.currentTimeMillis() / 1000L);
    state.activeAuctions().put("obj-1", auction);

    state.sessions().remove("bid-token-old");

    invokeFinishActiveAuction(engine, auction);

    assertTrue(waitForOutput(captured,
            "EVENT|AUCTION_ENDED|obj-1|seller-token||11.0|NO_WINNER"),
        captured.output());
  }

  private static void invokeFinishActiveAuction(AuctionEngine engine, CurrentAuction auction)
      throws Exception {
    Method method = AuctionEngine.class.getDeclaredMethod("finishActiveAuction",
        CurrentAuction.class);
    method.setAccessible(true);
    method.invoke(engine, auction);
  }

  private static boolean waitForOutput(CapturedSession captured, String needle)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 500;
    while (System.currentTimeMillis() < deadline) {
      if (captured.output().contains(needle)) {
        return true;
      }
      Thread.sleep(10);
    }
    return captured.output().contains(needle);
  }

  private static final class CapturedSession {
    private final StringWriter output = new StringWriter();
    private final ClientSession session =
        new ClientSession(new Socket(), new PrintWriter(output, true));

    private String output() {
      return output.toString();
    }
  }
}
