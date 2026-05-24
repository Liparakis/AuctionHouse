package gr.aueb.auctionhouse.common.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CurrentAuction {

  private static final double NORMAL_PHASE_CAP = 0.10;
  private static final double LATE_PHASE_CAP = 0.20;

  private final String objectId;
  private final String sellerToken;
  private final String sellerUsername;
  private final String description;
  private final double startingPrice;
  private final int durationSeconds;
  private final long startedAtEpochSecond;
  private final long endsAtEpochSecond;

  private final AtomicReference<BidState> bidState;

  public CurrentAuction(String objectId, String sellerToken, String description,
      double startingPrice, int durationSeconds) {
    this(objectId, sellerToken, "", description, startingPrice, durationSeconds,
        Instant.now().getEpochSecond());
  }

  public CurrentAuction(String objectId, String sellerToken, String description,
      double startingPrice, int durationSeconds, long startedAtEpochSecond) {
    this(objectId, sellerToken, "", description, startingPrice, durationSeconds,
        startedAtEpochSecond);
  }

  public CurrentAuction(String objectId, String sellerToken, String sellerUsername,
      String description, double startingPrice, int durationSeconds) {
    this(objectId, sellerToken, sellerUsername, description, startingPrice, durationSeconds,
        Instant.now().getEpochSecond());
  }

  public CurrentAuction(String objectId, String sellerToken, String sellerUsername,
      String description, double startingPrice, int durationSeconds, long startedAtEpochSecond) {
    this.objectId = objectId;
    this.sellerToken = sellerToken;
    this.sellerUsername = sellerUsername == null ? "" : sellerUsername;
    this.description = description;
    this.startingPrice = startingPrice;
    this.durationSeconds = durationSeconds;
    this.startedAtEpochSecond = startedAtEpochSecond;
    this.endsAtEpochSecond = startedAtEpochSecond + durationSeconds;
    this.bidState = new AtomicReference<>(new BidState(
        new BidSnapshot(startingPrice, null),
        List.of()
    ));
  }

  public String objectId() {
    return objectId;
  }

  public String sellerToken() {
    return sellerToken;
  }

  public String description() {
    return description;
  }

  public String sellerUsername() {
    return sellerUsername;
  }

  public double startingPrice() {
    return startingPrice;
  }

  public int durationSeconds() {
    return durationSeconds;
  }

  public BidSnapshot bidSnapshot() {
    return bidState.get().snapshot();
  }

  public List<BidRecord> bidHistory() {
    return bidState.get().history();
  }

  public long remainingSeconds() {
    return remainingSecondsAt(Instant.now().getEpochSecond());
  }

  public long remainingSecondsAt(long nowEpochSecond) {
    long remaining = endsAtEpochSecond - nowEpochSecond;
    return Math.max(0, remaining);
  }

  public boolean isClosedAt(long nowEpochSecond) {
    return remainingSecondsAt(nowEpochSecond) <= 0;
  }

  public BidWindow bidWindowAt(long nowEpochSecond) {
    BidSnapshot snapshot = bidSnapshot();
    boolean lateWindow = isLateWindow(nowEpochSecond);
    double capMultiplier = 1.0 + (lateWindow ? LATE_PHASE_CAP : NORMAL_PHASE_CAP);
    return new BidWindow(snapshot.highestBid(), snapshot.highestBid() * capMultiplier, lateWindow);
  }

  public boolean tryPlaceBid(String bidderToken, double amount, long nowEpochSecond) {
    return tryPlaceBid(bidderToken, "", amount, nowEpochSecond);
  }

  public boolean tryPlaceBid(String bidderToken, String bidderUsername, double amount,
      long nowEpochSecond) {
    while (true) {
      BidState current = bidState.get();
      BidWindow window = bidWindowAt(nowEpochSecond);
      if (isClosedAt(nowEpochSecond)
          || bidderToken == null
          || bidderToken.isBlank()
          || amount <= window.minimumAcceptedBid()
          || amount > window.maximumAcceptedBid()) {
        return false;
      }

      BidSnapshot nextSnapshot = new BidSnapshot(amount, bidderToken);
      List<BidRecord> nextHistory = insertDescending(current.history(),
          new BidRecord(bidderToken, bidderUsername == null ? "" : bidderUsername, amount,
              nowEpochSecond));
      if (bidState.compareAndSet(current, new BidState(nextSnapshot, nextHistory))) {
        return true;
      }
    }
  }

  private boolean isLateWindow(long nowEpochSecond) {
    long elapsed = Math.max(0, nowEpochSecond - startedAtEpochSecond);
    return elapsed >= Math.ceil(durationSeconds * 0.90d);
  }

  private static List<BidRecord> insertDescending(List<BidRecord> current, BidRecord next) {
    List<BidRecord> updated = new ArrayList<>(current.size() + 1);
    boolean inserted = false;
    for (BidRecord record : current) {
      if (!inserted && next.amount() > record.amount()) {
        updated.add(next);
        inserted = true;
      }
      updated.add(record);
    }
    if (!inserted) {
      updated.add(next);
    }
    return List.copyOf(updated);
  }

  public record BidSnapshot(double highestBid, String highestBidderToken) {
  }

  public record BidRecord(String bidderToken, String bidderUsername, double amount,
                          long placedAtEpochSecond) {
  }

  public record BidWindow(double minimumAcceptedBid, double maximumAcceptedBid,
                          boolean isLateWindow) {
  }

  private record BidState(BidSnapshot snapshot, List<BidRecord> history) {
  }
}
