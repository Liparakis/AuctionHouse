package gr.aueb.auctionhouse.server.auction;

import gr.aueb.auctionhouse.common.model.AuctionRequest;
import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.common.protocol.codec.Protocol;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.server.config.ServerValidation;
import gr.aueb.auctionhouse.server.state.ServerState;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
        Objects.requireNonNullElse(state.usernameForToken(next.sellerToken()), ""),
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
    System.out.println("[SERVER] finishAuction object=" + active.objectId()
        + " highestBid=" + active.bidSnapshot().highestBid()
        + " winner=" + active.bidSnapshot().highestBidderToken());

    updateSellerStatistics(active.sellerToken());

    TransactionCandidate candidate = firstEligibleCandidate(active, Set.of());
    if (candidate == null) {
      broadcastAuctionEnded(active, null);
      System.out.println("[SERVER] transactionSkipped object=" + active.objectId()
          + " reason=no_eligible_bidder");
      return;
    }

    state.pendingTransactions().put(active.objectId(), active);
    broadcastAuctionEnded(active, candidate);
    emitTransactionReady(active, candidate, false);
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

    Set<String> excludedUsernames = new HashSet<>();
    excludedUsernames.add(state.usernameForToken(failedBidderToken));
    TransactionCandidate next = firstEligibleCandidate(auction, excludedUsernames);
    if (next == null) {
      state.pendingTransactions().remove(objectId);
      state.broadcast(Protocol.event(EventType.TRANSACTION_FAILED, objectId, failedBidderToken));
      System.out.println("[SERVER] transactionFailedFinal object=" + objectId
          + " reason=no_fallback_bidder");
      return true;
    }

    emitTransactionReady(auction, next, true);
    System.out.println("[SERVER] transactionPromoted object=" + objectId + " nextBidder="
        + next.activeBidderToken() + " bid=" + next.amount());
    return true;
  }

  private void emitTransactionReady(CurrentAuction auction, TransactionCandidate candidate,
      boolean promoted) {
    ServerState.PeerEndpoint sellerEndpoint = state.activePeerForUsername(auction.sellerUsername());
    if (sellerEndpoint == null) {
      return;
    }
    state.broadcast(Protocol.event(
        promoted ? EventType.TRANSACTION_PROMOTED : EventType.TRANSACTION_READY,
        auction.objectId(),
        state.activeTokenForUsername(auction.sellerUsername()),
        candidate.activeBidderToken(),
        sellerEndpoint.ip(),
        Integer.toString(sellerEndpoint.port()),
        Double.toString(candidate.amount())
    ));
  }

  private void broadcastAuctionEnded(CurrentAuction active, TransactionCandidate candidate) {
    CurrentAuction.BidSnapshot snapshot = active.bidSnapshot();
    boolean awarded = candidate != null;
    String winnerToken = awarded ? candidate.activeBidderToken() : "";
    String status = awarded ? "AWARDED" : "NO_WINNER";

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

  private TransactionCandidate firstEligibleCandidate(CurrentAuction auction,
      Set<String> excludedUsernames) {
    List<CurrentAuction.BidRecord> ranked = auction.bidHistory();
    Set<String> seenUsernames = new HashSet<>();
    Set<String> connectedUsernames = state.connectedPeerUsernames();
    for (CurrentAuction.BidRecord record : ranked) {
      String bidderUsername = record.bidderUsername();
      if (bidderUsername == null
          || bidderUsername.isBlank()
          || bidderUsername.equals(auction.sellerUsername())
          || !seenUsernames.add(bidderUsername)
          || !connectedUsernames.contains(bidderUsername)
          || (excludedUsernames != null && excludedUsernames.contains(bidderUsername))) {
        continue;
      }
      String activeBidderToken = state.activeTokenForUsername(bidderUsername);
      if (activeBidderToken == null || activeBidderToken.isBlank()) {
        continue;
      }
      return new TransactionCandidate(activeBidderToken, bidderUsername, record.amount());
    }
    return null;
  }

  private void updateSellerStatistics(String sellerToken) {
    String username = state.usernameForToken(sellerToken);
    if (username == null || username.isBlank()) {
      System.out.println("[SERVER] statsSkip seller token=" + sellerToken + " reason=no_username");
      return;
    }
    state.userStats().compute(username, (_, stats) -> {
      ServerState.UserStats base = stats == null ? ServerState.UserStats.initial() : stats;
      ServerState.UserStats updated = base.incrementSeller();
      System.out.println("[SERVER] statsUpdate user=" + username
          + " sellerCount=" + updated.numAuctionsAsSeller()
          + " bidderCount=" + updated.numAuctionsAsBidder()
          + " reputation=" + updated.reputationScore());
      return updated;
    });
  }

  private void updateBidderStatistics(String bidderToken, boolean success) {
    String username = state.usernameForToken(bidderToken);
    if (username == null || username.isBlank()) {
      System.out.println("[SERVER] statsSkip bidder token=" + bidderToken + " reason=no_username");
      return;
    }
    state.userStats().compute(username, (_, stats) -> {
      ServerState.UserStats base = stats == null ? ServerState.UserStats.initial() : stats;
      ServerState.UserStats updated = success ? base.recordBidderSuccess()
          : base.recordAwardFailure();
      System.out.println("[SERVER] statsUpdate user=" + username
          + " sellerCount=" + updated.numAuctionsAsSeller()
          + " bidderCount=" + updated.numAuctionsAsBidder()
          + " reputation=" + updated.reputationScore());
      return updated;
    });
  }

  private record TransactionCandidate(String activeBidderToken, String bidderUsername,
                                      double amount) {
  }
}
