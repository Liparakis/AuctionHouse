package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.builder.CommandWire;
import gr.aueb.auctionhouse.common.protocol.enums.ErrorCode;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;
import gr.aueb.auctionhouse.peer.connection.PeerConnection;
import gr.aueb.auctionhouse.peer.core.utils.PeerSessionSupport;
import gr.aueb.auctionhouse.peer.core.utils.PeerTransactionHandler;
import gr.aueb.auctionhouse.peer.transaction.ObjectMetadataStore;
import gr.aueb.auctionhouse.peer.transaction.PeerTransactionServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class AuctionAutoSession {

  private static final Logger LOG = Logger.getLogger(AuctionAutoSession.class.getName());
  private static final String LOG_PREFIX = "[PEER-AUTO]";

  private static final double BID_ATTEMPT_PROBABILITY = 0.60;
  private static final double BID_MULTIPLIER_MAX_STEP = 0.10;
  private static final double AUTO_AUCTION_MIN_START = 5.0;
  private static final double AUTO_AUCTION_MAX_START = 50.0;
  private static final int AUTO_AUCTION_MIN_DURATION = 30;
  private static final int AUTO_AUCTION_MAX_DURATION = 120;
  private static final int AUTO_AUCTION_MAX_DELAY_SEC = 120;
  private static final String DEFAULT_ITEM_DESCRIPTION = "Some item";

  private static final long DEFAULT_RESPONSE_TIMEOUT_MS = 3_000;

  private final String host;
  private final int port;

  AuctionAutoSession(String host, int port) {
    this.host = host;
    this.port = port;
  }

  void run(String username,
      String password,
      int listenPort,
      double maxBid,
      long pollMs,
      Double initialAuctionStartPrice,
      Integer initialAuctionDurationSec) {

    boolean seedAuctionPending = shouldCreateAuction(initialAuctionStartPrice,
        initialAuctionDurationSec);
    int reconnectAttempt = 0;
    while (!Thread.currentThread().isInterrupted()) {
      try (PeerConnection connection = PeerSessionSupport.openConnection(host, port)) {
        reconnectAttempt = 0;
        LOG.info(LOG_PREFIX + " Connected to " + host + ":" + port);
        consumeWelcomeBanner(connection);

        String token = authenticate(connection, username, password);
        Path sharedRoot = ObjectMetadataStore.peerRoot(peerId(username));

        try (PeerTransactionServer txnServer = new PeerTransactionServer(listenPort, token,
            sharedRoot)) {
          txnServer.start();
          registerPeerListener(connection, txnServer.listenPort());

          Random random = new Random();
          AtomicBoolean auctionDue = new AtomicBoolean(false);
          ScheduledExecutorService auctionScheduler = startAuctionScheduler(auctionDue, random);
          try {
            if (seedAuctionPending) {
              enqueueAuctionWithMetadata(connection, sharedRoot, initialAuctionStartPrice,
                  initialAuctionDurationSec);
              seedAuctionPending = false;
            }
            runPollingLoop(connection, token, maxBid, pollMs, sharedRoot, random, auctionDue);
          } finally {
            auctionScheduler.shutdownNow();
          }
        }
      } catch (IOException | RuntimeException ex) {
        long backoffMs = PeerSessionSupport.reconnectBackoffMs(reconnectAttempt++);
        LOG.warning(LOG_PREFIX + " Server offline/unreachable (" + ex.getMessage()
            + "). Reconnect in " + backoffMs + " ms.");
        PeerSessionSupport.sleepQuietly(backoffMs);
      }
    }
  }

  private String authenticate(PeerConnection connection, String username, String password) {
    tryRegister(connection, username, password);
    return login(connection, username, password);
  }

  private void tryRegister(PeerConnection connection, String username, String password) {
    ResponseMessage resp = AuctionCommandSender.sendExpect(
        connection,
        CommandWire.register(username, password),
        DEFAULT_RESPONSE_TIMEOUT_MS,
        AuctionCommandSender.ok(OkCode.REGISTERED),
        AuctionCommandSender.errAny()
    );
    // ok if user already exists
    if (resp != null && resp.kind() == ResponseKind.ERR
        && resp.errorCode() != ErrorCode.USER_EXISTS) {
      throw new IllegalStateException("REGISTER failed: " + AuctionCommandSender.rawOf(resp));
    }
    LOG.info(LOG_PREFIX + " REGISTER => " + AuctionCommandSender.rawOf(resp));
  }

  private String login(PeerConnection connection, String username, String password) {
    ResponseMessage resp = AuctionCommandSender.sendExpect(
        connection,
        CommandWire.login(username, password),
        DEFAULT_RESPONSE_TIMEOUT_MS,
        AuctionCommandSender.ok(OkCode.TOKEN),
        AuctionCommandSender.errAny()
    );
    AuctionCommandSender.requireOk(resp, OkCode.TOKEN, "LOGIN failed");
    LOG.info(LOG_PREFIX + " LOGIN => " + AuctionCommandSender.rawOf(resp));
    return AuctionCommandSender.field(resp, 0);
  }

  private void registerPeerListener(PeerConnection connection, int listenPort) {
    String listenIp = PeerSessionSupport.advertisedListenIp(connection);
    ResponseMessage resp = AuctionCommandSender.sendExpect(
        connection,
        listenIp == null
            ? CommandWire.peerListen(listenPort)
            : CommandWire.peerListen(listenIp, listenPort),
        DEFAULT_RESPONSE_TIMEOUT_MS,
        AuctionCommandSender.ok(OkCode.PEER_REGISTERED),
        AuctionCommandSender.errAny()
    );
    AuctionCommandSender.requireOk(resp, OkCode.PEER_REGISTERED, "PEER_LISTEN failed");
  }

  private void runPollingLoop(PeerConnection connection,
      String token,
      double maxBid,
      long pollMs,
      Path sharedRoot,
      Random random,
      AtomicBoolean auctionDue) {
    while (connection.isAlive()) {
      maybeGenerateAuction(connection, sharedRoot, random, auctionDue);
      drainTransactionEvents(connection, token, sharedRoot);

      ResponseMessage current = pollActiveAuctions(connection);
      if (current == null) {
        break;
      }
      if (current.okCode() == OkCode.NO_AUCTION) {
        PeerSessionSupport.sleepQuietly(pollMs);
        continue;
      }
      if (current.okCode() != OkCode.ACTIVE_AUCTIONS) {
        LOG.warning(LOG_PREFIX + " Unexpected GET_CURRENT_AUCTION response: " + current.raw());
        PeerSessionSupport.sleepQuietly(pollMs);
        continue;
      }

      for (AuctionProtocolViews.ActiveAuctionSummaryView auction
          : AuctionProtocolViews.activeAuctions(current)) {
        maybePlaceBid(connection, token, maxBid, auction.objectId(), random);
      }
      PeerSessionSupport.sleepQuietly(pollMs);
    }
  }

  private ResponseMessage pollActiveAuctions(PeerConnection connection) {
    return AuctionCommandSender.sendExpect(
        connection,
        CommandWire.getCurrentAuctions(),
        DEFAULT_RESPONSE_TIMEOUT_MS,
        AuctionCommandSender.ok(OkCode.NO_AUCTION),
        AuctionCommandSender.ok(OkCode.ACTIVE_AUCTIONS),
        AuctionCommandSender.errAny()
    );
  }

  private void maybePlaceBid(PeerConnection connection,
      String token,
      double maxBid,
      String objectId,
      Random random) {
    ResponseMessage details = AuctionCommandSender.sendExpect(
        connection,
        CommandWire.getAuctionDetails(objectId),
        DEFAULT_RESPONSE_TIMEOUT_MS,
        AuctionCommandSender.ok(OkCode.CURRENT_AUCTION),
        AuctionCommandSender.errAny()
    );
    if (details == null || details.kind() != ResponseKind.OK
        || details.okCode() != OkCode.CURRENT_AUCTION) {
      return;
    }

    AuctionProtocolViews.CurrentAuctionView auction = AuctionProtocolViews.currentAuction(details, 0);
    double highestBid = AuctionCommandSender.parseDouble(auction.highestBid());
    long remainingSec = auction.remainingSec();
    boolean lateWindow = remainingSec <= Math.max(1, Math.round(auction.durationSec() * 0.10d));

    if (shouldSkipBidding(token, auction.seller(), auction.highestBidder(), highestBid, maxBid,
        remainingSec, random)) {
      return;
    }

    double maxStep = lateWindow ? 0.20 : BID_MULTIPLIER_MAX_STEP;
    double candidateBid = Math.min(maxBid,
        highestBid * (1.0 + random.nextDouble() * maxStep));
    if (candidateBid <= highestBid) {
      return;
    }

    ResponseMessage bidResp = AuctionCommandSender.sendExpect(
        connection,
        CommandWire.placeBid(auction.objectId(), candidateBid),
        DEFAULT_RESPONSE_TIMEOUT_MS,
        AuctionCommandSender.ok(OkCode.BID_ACCEPTED),
        AuctionCommandSender.errAny()
    );
    LOG.info(LOG_PREFIX + " BID " + auction.objectId() + " " + candidateBid + " => "
        + AuctionCommandSender.rawOf(bidResp));
  }

  // handles txn events
  private void drainTransactionEvents(PeerConnection connection, String token, Path sharedRoot) {
    ResponseMessage event;
    while ((event = connection.pollEvent(10)) != null) {
      if (event.kind() == ResponseKind.EVENT
          && (event.eventType() == EventType.TRANSACTION_READY
          || event.eventType() == EventType.TRANSACTION_PROMOTED)) {
        PeerTransactionHandler.handle(
            LOG_PREFIX, event, token, sharedRoot, connection, DEFAULT_RESPONSE_TIMEOUT_MS,
            id -> LOG.info(LOG_PREFIX + " TXN complete recorded for object=" + id),
            null
        );
      }
    }
  }

  private static ScheduledExecutorService startAuctionScheduler(AtomicBoolean dueFlag,
      Random random) {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "peer-auto-auction-generator");
      t.setDaemon(true);
      return t;
    });
    scheduleNextAuctionTick(scheduler, dueFlag, random);
    return scheduler;
  }

  private static void scheduleNextAuctionTick(ScheduledExecutorService scheduler,
      AtomicBoolean dueFlag,
      Random random) {
    long delaySec = (long) (random.nextDouble() * AUTO_AUCTION_MAX_DELAY_SEC);
    scheduler.schedule(() -> {
      dueFlag.set(true);
      if (!scheduler.isShutdown()) {
        scheduleNextAuctionTick(scheduler, dueFlag, random);
      }
    }, delaySec, TimeUnit.SECONDS);
  }

  private void maybeGenerateAuction(PeerConnection connection,
      Path sharedRoot,
      Random random,
      AtomicBoolean auctionDue) {
    if (!auctionDue.compareAndSet(true, false)) {
      return;
    }
    try {
      enqueueAuctionWithMetadata(connection, sharedRoot, randomStartPrice(random),
          randomDurationSec(random));
    } catch (RuntimeException ex) {
      LOG.warning(LOG_PREFIX + " Random auction enqueue failed: " + ex.getMessage());
    }
  }

  private void enqueueAuctionWithMetadata(PeerConnection connection,
      Path sharedRoot,
      double startPrice,
      int durationSec) {
    Path pending = null;
    try {
      pending = ObjectMetadataStore.createPending(sharedRoot, DEFAULT_ITEM_DESCRIPTION, startPrice,
          durationSec);
      ResponseMessage resp = AuctionCommandSender.sendExpect(
          connection,
          CommandWire.requestAuction(DEFAULT_ITEM_DESCRIPTION, startPrice, durationSec),
          DEFAULT_RESPONSE_TIMEOUT_MS,
          AuctionCommandSender.ok(OkCode.AUCTION_ENQUEUED),
          AuctionCommandSender.errAny()
      );
      AuctionCommandSender.requireOk(resp, OkCode.AUCTION_ENQUEUED, "SEND_AUCTION_REQUEST failed");
      String objectId = AuctionCommandSender.field(resp, 0);
      Path finalPath = ObjectMetadataStore.bindPendingToObject(pending, sharedRoot, objectId);
      LOG.info(LOG_PREFIX + " SEND_AUCTION_REQUEST => " + AuctionCommandSender.rawOf(resp)
          + " metadata=" + finalPath.toAbsolutePath());
    } catch (Exception ex) {
      deletePendingQuietly(pending);
      throw new IllegalStateException("SEND_AUCTION_REQUEST failed: " + ex.getMessage(), ex);
    }
  }

  private static void deletePendingQuietly(Path pending) {
    if (pending == null) {
      return;
    }
    try {
      Files.deleteIfExists(pending);
    } catch (IOException ignored) {
    }
  }

  private static boolean shouldSkipBidding(String token, String sellerToken, String highestBidder,
      double highestBid, double maxBid,
      long remainingSec, Random random) {
    return remainingSec <= 0
        || token.equals(sellerToken)
        || token.equals(highestBidder)
        || highestBid >= maxBid
        || random.nextDouble() > BID_ATTEMPT_PROBABILITY;
  }

  private static boolean shouldCreateAuction(Double price, Integer durationSec) {
    return price != null && durationSec != null && price > 0 && durationSec > 0;
  }

  private static double randomStartPrice(Random random) {
    return AUTO_AUCTION_MIN_START + random.nextDouble() * (AUTO_AUCTION_MAX_START
        - AUTO_AUCTION_MIN_START);
  }

  private static int randomDurationSec(Random random) {
    return AUTO_AUCTION_MIN_DURATION + random.nextInt(
        AUTO_AUCTION_MAX_DURATION - AUTO_AUCTION_MIN_DURATION + 1);
  }

  // cleans name for folder
  static String peerId(String username) {
    return username == null
        ? "peer"
        : username.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
  }

  private static void consumeWelcomeBanner(PeerConnection connection) {
    ResponseMessage welcome = connection.readResponse(DEFAULT_RESPONSE_TIMEOUT_MS);
    if (welcome != null) {
      LOG.info(LOG_PREFIX + " WELCOME => " + welcome.raw());
    }
  }
}
