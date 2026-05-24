package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;
import gr.aueb.auctionhouse.peer.connection.PeerConnection;
import gr.aueb.auctionhouse.peer.core.utils.PeerTransactionHandler;
import gr.aueb.auctionhouse.peer.ui.PeerFrameConsole;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

// keeps auction screen updated
final class AuctionLiveTracker {

  private static final Logger LOG = Logger.getLogger(AuctionLiveTracker.class.getName());
  private static final String LOG_PREFIX = "[PEER]";

  private final PeerConnection connection;
  private final PeerFrameConsole console;
  private final AtomicReference<String> authToken;
  private final AtomicReference<Path> sharedRootRef;
  private final long defaultTimeoutMs;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread t = new Thread(r, "peer-auction-live-tracker");
        t.setDaemon(true);
        return t;
      });

  private final AtomicReference<ActiveAuction> active = new AtomicReference<>();
  private final AtomicReference<TransientStatus> transientStatus = new AtomicReference<>();

  AuctionLiveTracker(PeerConnection connection, PeerFrameConsole console,
      AtomicReference<String> authToken, AtomicReference<Path> sharedRootRef,
      long defaultTimeoutMs) {
    this.connection = connection;
    this.console = console;
    this.authToken = authToken;
    this.sharedRootRef = sharedRootRef;
    this.defaultTimeoutMs = defaultTimeoutMs;
  }

  void start() {
    scheduler.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
  }

  void stop() {
    scheduler.shutdownNow();
    active.set(null);
    transientStatus.set(null);
  }

  void startOrRefresh(String objectId, String description, String seller, String highestBid,
      String highestBidder, long remainingSec, long offsetMs) {
    long now = System.currentTimeMillis();
    long endAtMs = now + Math.max(0, remainingSec * 1000L - offsetMs);
    active.set(new ActiveAuction(objectId, description, seller, highestBid, highestBidder, endAtMs,
        offsetMs, now));
    renderCountdown(active.get(), Math.max(0, (endAtMs - now + 999) / 1000));
  }

  boolean hasActiveAuction() {
    return active.get() != null;
  }

  String currentObjectId() {
    ActiveAuction current = active.get();
    return current == null ? null : current.objectId();
  }

  void cancelByUserCommand() {
    active.set(null);
    transientStatus.set(null);
  }

  void markAuctionCancelledAbruptly() {
    active.set(null);
    transientStatus.set(null);
  }

  @SuppressWarnings("SameParameterValue")
  void showTemporaryStatus(String message, long durationMs) {
    if (message == null || message.isBlank()) {
      transientStatus.set(null);
      return;
    }
    transientStatus.set(
        new TransientStatus(message, System.currentTimeMillis() + Math.max(0, durationMs)));
  }

  void refreshNow() {
    ActiveAuction current = active.get();
    if (current == null) {
      return;
    }
    long remainingSec = Math.max(0,
        (current.endAtEpochMs() - System.currentTimeMillis() + 999) / 1000);
    renderCountdown(current, remainingSec);
  }

  private void tick() {
    try {
      drainEvents();
      ActiveAuction current = active.get();
      if (current == null) {
        return;
      }
      long now = System.currentTimeMillis();
      long remainingSec = Math.max(0, (current.endAtEpochMs() - now + 999) / 1000);
      renderCountdown(current, remainingSec);
      if (remainingSec == 0) {
        active.set(null);
      }
    } catch (Exception ex) {
      // logs tick error
      LOG.warning(LOG_PREFIX + " Live tracker tick error: " + ex.getMessage());
    }
  }

  private void drainEvents() {
    ResponseMessage event;
    while ((event = connection.pollEvent(1)) != null) {
      if (event.kind() == ResponseKind.EVENT) {
        handleEvent(event);
      }
    }
  }

  private void handleEvent(ResponseMessage event) {
    switch (event.eventType()) {
      case AUCTION_ENDED -> handleAuctionEnded(event);
      case AUCTION_CANCELLED -> handleAuctionCancelled(event);
      case BID_ACCEPTED -> handleBidAccepted(event);
      case TRANSACTION_READY, TRANSACTION_PROMOTED -> handleTransactionReady(event);
      default -> { }
    }
  }

  private void handleAuctionEnded(ResponseMessage event) {
    AuctionProtocolViews.AuctionEndedView view = AuctionProtocolViews.auctionEnded(event);
    active.set(null);
    transientStatus.set(null);
    console.addInfo("AUCTION_ENDED\n"
        + "- object: " + view.objectId() + "\n"
        + "- seller: " + view.seller() + "\n"
        + "- winner: " + AuctionProtocolViews.blankOr(view.winner(), "-") + "\n"
        + "- finalBid: " + view.finalBid() + "\n"
        + "- status: " + view.status());
  }

  private void handleAuctionCancelled(ResponseMessage event) {
    AuctionProtocolViews.AuctionCancelledView view = AuctionProtocolViews.auctionCancelled(event);
    active.set(null);
    transientStatus.set(null);
    console.addWarn("AUCTION_CANCELLED\n"
        + "- object: " + view.objectId() + "\n"
        + "- disconnected: " + AuctionProtocolViews.blankOr(view.disconnected(), "-") + "\n"
        + "- role: " + view.role() + "\n"
        + "- reason: " + view.reason());
  }

  private void handleBidAccepted(ResponseMessage event) {
    ActiveAuction current = active.get();
    if (current == null) {
      return;
    }
    String objectId = AuctionCommandSender.field(event, 0);
    if (!current.objectId().equals(objectId)) {
      return;
    }
    String highestBidder = AuctionCommandSender.field(event, 1);
    String highestBid = AuctionCommandSender.field(event, 2);
    long remainingSec = AuctionCommandSender.parseLong(AuctionCommandSender.field(event, 3));
    long now = System.currentTimeMillis();
    long adjustedEndAt = now + Math.max(0, remainingSec * 1000L - current.offsetMs());
    ActiveAuction updated = new ActiveAuction(current.objectId(), current.description(),
        current.seller(), highestBid, highestBidder, adjustedEndAt, current.offsetMs(), now);
    active.set(updated);
    renderCountdown(updated, Math.max(0, (adjustedEndAt - now + 999) / 1000));
  }

  private void handleTransactionReady(ResponseMessage event) {
    String token = authToken.get();
    if (token == null || token.isBlank()) {
      return;
    }
    PeerTransactionHandler.handle(LOG_PREFIX, event, token, sharedRootRef.get(), connection,
        defaultTimeoutMs, id -> console.addInfo("TRANSACTION_COMPLETED\n- object: " + id),
        console::addWarn);
  }

  private void renderCountdown(ActiveAuction current, long remainingSec) {
    TransientStatus status = transientStatus.get();
    if (status != null && System.currentTimeMillis() > status.expiresAtEpochMs()) {
      transientStatus.compareAndSet(status, null);
      status = null;
    }
    console.addAuction(
        "AUCTION_DETAILS\n" + "- object: " + current.objectId() + "\n" + "- description: "
            + current.description() + "\n" + "- seller: " + current.seller() + "\n"
            + "- highestBid: " + current.highestBid() + "\n" + "- highestBidder: " + blankOr(
            current.highestBidder(), "-") + "\n" + "- remainingSec: " + remainingSec + "\n"
            + "- offsetMs: " + current.offsetMs() + "\n" + "- syncedAtEpochMs: "
            + current.syncedAtEpochMs() + (status == null ? ""
            : "\n- status: " + status.message()));
  }

  @SuppressWarnings("SameParameterValue")
  private static String blankOr(String value, String fallback) {
    return AuctionProtocolViews.blankOr(value, fallback);
  }

  private record ActiveAuction(String objectId, String description, String seller,
                               String highestBid,
                               String highestBidder, long endAtEpochMs, long offsetMs,
                               long syncedAtEpochMs) {

  }

  private record TransientStatus(String message, long expiresAtEpochMs) {

  }
}
