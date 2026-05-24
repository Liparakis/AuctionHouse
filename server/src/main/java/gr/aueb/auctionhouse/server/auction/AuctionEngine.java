package gr.aueb.auctionhouse.server.auction;

import gr.aueb.auctionhouse.common.model.AuctionRequest;
import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.common.protocol.codec.Protocol;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.server.config.ServerValidation;
import gr.aueb.auctionhouse.server.state.ServerState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AuctionEngine {

  private final ServerState state;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  public AuctionEngine(ServerState state) {
    this.state = state;
  }

  public void start() {
    scheduler.scheduleAtFixedRate(this::tick, 0, ServerValidation.AUCTION_TICK_MS,
        TimeUnit.MILLISECONDS);
  }

  public void stop() {
    scheduler.shutdownNow();
  }

  private void tick() {
    try {
      state.auctionStateLock().lock();
      try {
        finishExpiredAuctions();
        fillAuctionSlots();
      } finally {
        state.auctionStateLock().unlock();
      }
    } catch (Exception ex) {
      System.err.println("[SERVER] Auction engine tick failed: " + ex.getMessage());
    }
  }

  private void finishExpiredAuctions() {
    long now = System.currentTimeMillis() / 1000L;
    for (CurrentAuction auction : state.activeAuctionList()) {
      if (auction.isClosedAt(now)) {
        finishActiveAuction(auction);
      }
    }
  }

  private void fillAuctionSlots() {
    while (state.hasAuctionCapacity()) {
      AuctionRequest next = state.pollNextAuctionByReputation();
      if (next == null) {
        return;
      }
      startAuction(next);
    }
  }

  private void startAuction(AuctionRequest next) {
    CurrentAuction auction = new CurrentAuction(
        next.objectId(),
        next.sellerToken(),
        next.description(),
        next.startingPrice(),
        next.durationSeconds()
    );
    state.activeAuctions().put(auction.objectId(), auction);
    System.out.println("[SERVER] startAuction object=" + auction.objectId()
        + " seller=" + auction.sellerToken()
        + " startBid=" + auction.startingPrice()
        + " durationSec=" + auction.durationSeconds());

    CurrentAuction.BidSnapshot snapshot = auction.bidSnapshot();
    state.broadcast(Protocol.event(
        EventType.AUCTION_STARTED,
        auction.objectId(),
        auction.sellerToken(),
        Double.toString(snapshot.highestBid()),
        Long.toString(auction.remainingSeconds())
    ));
  }

  private void finishActiveAuction(CurrentAuction active) {
    state.activeAuctions().remove(active.objectId());
    state.pendingTransactions().put(active.objectId(), active);
    System.out.println("[SERVER] finishAuction object=" + active.objectId()
        + " highestBid=" + active.bidSnapshot().highestBid()
        + " winner=" + active.bidSnapshot().highestBidderToken());

    CurrentAuction.BidSnapshot snapshot = active.bidSnapshot();
    updateSellerStatistics(active.sellerToken());
    broadcastAuctionEnded(active, snapshot);
    notifyTransactionReady(active);
  }

  private void notifyTransactionReady(CurrentAuction active) {
    List<CurrentAuction.BidRecord> ranked = active.rankedEligibleBids(
        state.connectedPeerTokens(), Set.of());
    if (ranked.isEmpty()) {
      System.out.println("[SERVER] transactionSkipped object=" + active.objectId()
          + " reason=no_eligible_bidder");
      return;
    }
    emitTransactionReady(active, ranked.getFirst().bidderToken(), ranked.getFirst().amount(), false);
  }

  public boolean recordTransactionSuccess(String objectId, String bidderToken) {
    CurrentAuction auction = state.pendingTransactions().remove(objectId);
    if (auction == null) {
      return false;
    }
    System.out.println("[SERVER] transactionComplete object=" + objectId + " bidder=" + bidderToken);
    updateBidderStatistics(bidderToken, true);
    state.broadcast(Protocol.event(EventType.TRANSACTION_COMPLETED, objectId, bidderToken));
    return true;
  }

  public boolean recordTransactionFailure(String objectId, String failedBidderToken) {
    CurrentAuction auction = state.pendingTransactions().get(objectId);
    if (auction == null) {
      return false;
    }
    System.out.println("[SERVER] transactionFailed object=" + objectId + " bidder="
        + failedBidderToken);
    updateBidderStatistics(failedBidderToken, false);

    Set<String> excluded = new HashSet<>();
    excluded.add(failedBidderToken);
    List<CurrentAuction.BidRecord> ranked = auction.rankedEligibleBids(
        state.connectedPeerTokens(), excluded);
    if (ranked.isEmpty()) {
      state.pendingTransactions().remove(objectId);
      state.broadcast(Protocol.event(EventType.TRANSACTION_FAILED, objectId, failedBidderToken));
      System.out.println("[SERVER] transactionFailedFinal object=" + objectId
          + " reason=no_fallback_bidder");
      return true;
    }

    CurrentAuction.BidRecord next = ranked.getFirst();
    emitTransactionReady(auction, next.bidderToken(), next.amount(), true);
    System.out.println("[SERVER] transactionPromoted object=" + objectId + " nextBidder="
        + next.bidderToken() + " bid=" + next.amount());
    return true;
  }

  private void emitTransactionReady(CurrentAuction auction, String bidderToken, double amount,
      boolean promoted) {
    ServerState.PeerEndpoint sellerEndpoint = state.activePeers().get(auction.sellerToken());
    if (sellerEndpoint == null) {
      return;
    }
    state.broadcast(Protocol.event(
        promoted ? EventType.TRANSACTION_PROMOTED : EventType.TRANSACTION_READY,
        auction.objectId(),
        auction.sellerToken(),
        bidderToken,
        sellerEndpoint.ip(),
        Integer.toString(sellerEndpoint.port()),
        Double.toString(amount)
    ));
  }

  private void broadcastAuctionEnded(CurrentAuction active, CurrentAuction.BidSnapshot snapshot) {
    boolean sold = snapshot.highestBidderToken() != null;
    String winnerToken = sold ? snapshot.highestBidderToken() : "";
    String status = sold ? "SOLD" : "NO_WINNER";

    state.broadcast(Protocol.event(
        EventType.AUCTION_ENDED,
        active.objectId(),
        active.sellerToken(),
        winnerToken,
        Double.toString(snapshot.highestBid()),
        status,
        active.description(),
        Double.toString(active.startingPrice()),
        Integer.toString(active.durationSeconds())
    ));
  }

  private void updateSellerStatistics(String sellerToken) {
    String username = state.usernameForToken(sellerToken);
    if (username == null || username.isBlank()) {
      return;
    }
    state.userStats().compute(username, (_, stats) -> {
      ServerState.UserStats base = stats == null ? ServerState.UserStats.initial() : stats;
      return base.incrementSeller();
    });
  }

  private void updateBidderStatistics(String bidderToken, boolean success) {
    String username = state.usernameForToken(bidderToken);
    if (username == null || username.isBlank()) {
      return;
    }
    state.userStats().compute(username, (_, stats) -> {
      ServerState.UserStats base = stats == null ? ServerState.UserStats.initial() : stats;
      return success ? base.recordBidderSuccess() : base.recordAwardFailure();
    });
  }
}
