package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.builder.CommandWire;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;
import gr.aueb.auctionhouse.peer.connection.PeerConnection;
import gr.aueb.auctionhouse.peer.core.utils.PeerSessionSupport;
import gr.aueb.auctionhouse.peer.transaction.PeerTransactionServer;
import gr.aueb.auctionhouse.peer.ui.PeerConsoleFormatter;
import gr.aueb.auctionhouse.peer.ui.PeerFrameConsole;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

final class AuctionInteractiveSession {

  private static final Logger LOG = Logger.getLogger(AuctionInteractiveSession.class.getName());
  private static final String LOG_PREFIX = "[PEER]";

  private static final long DEFAULT_RESPONSE_TIMEOUT_MS = 3_000;

  private final String host;
  private final int port;

  AuctionInteractiveSession(String host, int port) {
    this.host = host;
    this.port = port;
  }

  void run() throws IOException {
    int reconnectAttempt = 0;
    try (PeerFrameConsole console = new PeerFrameConsole(host,
        port); Terminal terminal = TerminalBuilder.builder().system(true).build()) {

      LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();
      console.attachLineReader(lineReader);

      // keeps session stuff
      AtomicReference<Path> sharedRoot = new AtomicReference<>();
      AtomicReference<String> token = new AtomicReference<>();
      AtomicReference<PeerTransactionServer> txnServer = new AtomicReference<>();

      while (!Thread.currentThread().isInterrupted()) {
        try (PeerConnection connection = PeerSessionSupport.openConnection(host, port)) {
          AuctionLiveTracker tracker = new AuctionLiveTracker(connection, console, token,
              sharedRoot, DEFAULT_RESPONSE_TIMEOUT_MS);
          tracker.start();
          reconnectAttempt = 0;

          LOG.info(LOG_PREFIX + " Connected to " + host + ":" + port);
          console.addInfo("Connected to " + host + ":" + port);

          greetAndPing(connection, console);

          String exitReason = runCommandLoop(lineReader, connection, token, txnServer, sharedRoot,
              tracker, console);

          tracker.stop();
          if (exitReason == null) {
            // input closed
            AuctionSessionAutomator.closeInteractiveTxnServer(txnServer);
            token.set(null);
            console.addInfo("Input stream closed.");
            return;
          }
          if ("exit".equals(exitReason)) {
            return;
          }
        } catch (IOException | RuntimeException ex) {
          AuctionSessionAutomator.closeInteractiveTxnServer(txnServer);
          token.set(null);
          long backoffMs = PeerSessionSupport.reconnectBackoffMs(reconnectAttempt++);
          LOG.warning(
              LOG_PREFIX + " Server offline/unreachable (" + ex.getMessage() + "). Reconnect in "
                  + backoffMs + " ms.");
          console.addWarn(
              "Server offline/unreachable (" + ex.getMessage() + "). Reconnect in " + backoffMs
                  + " ms.");
          PeerSessionSupport.sleepQuietly(backoffMs);
        }
      }
    }
  }

  // shows welcome and ping
  private void greetAndPing(PeerConnection connection, PeerFrameConsole console) {
    String welcome = connection.readMeaningfulResponse(DEFAULT_RESPONSE_TIMEOUT_MS);
    if (welcome != null) {
      displayRawResponse(console, welcome);
    }
    AuctionCommandSender.CommandResult pong = AuctionCommandSender.send(connection,
        CommandWire.ping(), DEFAULT_RESPONSE_TIMEOUT_MS);
    if (pong != null) {
      displayResponse(console, pong.response());
    }
  }

  // runs commands until done
  private String runCommandLoop(LineReader lineReader, PeerConnection connection,
      AtomicReference<String> token, AtomicReference<PeerTransactionServer> txnServer,
      AtomicReference<Path> sharedRoot, AuctionLiveTracker tracker, PeerFrameConsole console) {
    while (true) {
      String line = readLine(lineReader, console);

      if (line == null) {
        return null;
      }
      if ("exit".equalsIgnoreCase(line.trim())) {
        LOG.info(LOG_PREFIX + " Closing connection.");
        console.addInfo("Closing connection.");
        AuctionSessionAutomator.closeInteractiveTxnServer(txnServer);
        return "exit";
      }

      String outcome = dispatchCommand(line, connection, token, txnServer, sharedRoot, tracker,
          console);
      if ("reconnect".equals(outcome)) {
        return "reconnect";
      }
    }
  }

  private String readLine(LineReader lineReader, PeerFrameConsole console) {
    try {
      console.beginInput();
      return lineReader.readLine("> ");
    } catch (UserInterruptException ex) {
      return "";
    } catch (EndOfFileException ex) {
      return null;
    } finally {
      console.endInput();
    }
  }

  // sends one command
  private String dispatchCommand(String line, PeerConnection connection,
      AtomicReference<String> token, AtomicReference<PeerTransactionServer> txnServer,
      AtomicReference<Path> sharedRoot, AuctionLiveTracker tracker, PeerFrameConsole console) {
    try {
      AuctionSessionAutomator.PendingAuctionFile pending = token.get() == null ? null
          : AuctionSessionAutomator.maybePreparePendingAuction(line, sharedRoot.get(), console);

      String wireCommand = normalizeInteractiveCommand(line, sharedRoot.get(), txnServer.get(),
          tracker, console);

      AuctionCommandSender.CommandResult result = AuctionCommandSender.send(connection, wireCommand,
          DEFAULT_RESPONSE_TIMEOUT_MS);

      if (result != null && result.response() != null) {
        processResult(line, result, pending, sharedRoot, token, txnServer, connection, tracker,
            console);
      } else if (!connection.isAlive()) {
        LOG.warning(LOG_PREFIX + " Connection dropped. Reconnecting...");
        tracker.markAuctionCancelledAbruptly();
        console.addWarn("Server connection dropped. Reconnecting...");
        AuctionSessionAutomator.closeInteractiveTxnServer(txnServer);
        token.set(null);
        return "reconnect";
      }
    } catch (RuntimeException ex) {
      LOG.warning(LOG_PREFIX + " Command send failed (" + ex.getMessage() + "). Reconnecting...");
      tracker.markAuctionCancelledAbruptly();
      console.addWarn("Command send failed (" + ex.getMessage() + "). Reconnecting...");
      AuctionSessionAutomator.closeInteractiveTxnServer(txnServer);
      token.set(null);
      return "reconnect";
    }
    return null;
  }

  private static String normalizeInteractiveCommand(String line, Path sharedRoot,
      PeerTransactionServer txnServer, AuctionLiveTracker tracker, PeerFrameConsole console) {
    String command = AuctionCommandParser.normalizeInteractiveCommand(line, sharedRoot, txnServer,
        console);
    String[] args = AuctionCommandParser.parseCommandArgs(command);
    String currentObjectId = tracker.currentObjectId();

    if (AuctionCommandParser.isBidCommand(command) && args.length == 1 && currentObjectId != null
        && !currentObjectId.isBlank()) {
      return CommandWire.placeBid(currentObjectId, AuctionCommandSender.parseDouble(args[0]));
    }
    if (AuctionCommandParser.isGetDetailsCommand(command) && args.length == 0
        && currentObjectId != null && !currentObjectId.isBlank()) {
      return CommandWire.getAuctionDetails(currentObjectId);
    }
    return command;
  }

  // handles command result
  private void processResult(String line, AuctionCommandSender.CommandResult result,
      AuctionSessionAutomator.PendingAuctionFile pending, AtomicReference<Path> sharedRoot,
      AtomicReference<String> token, AtomicReference<PeerTransactionServer> txnServer,
      PeerConnection connection, AuctionLiveTracker tracker, PeerFrameConsole console) {
    AuctionSessionAutomator.finalizePendingAuction(pending, sharedRoot.get(), result.response(),
        console);
    AuctionSessionAutomator.automateInteractiveSession(line, connection, result.response(), token,
        txnServer, sharedRoot, console, DEFAULT_RESPONSE_TIMEOUT_MS);

    if (!shouldSuppressStandardResponse(line, result, tracker)) {
      displayResponse(console, result.response());
    }
    handleAuctionState(line, result, tracker, console);
  }

  private static void handleAuctionState(String typedCommand,
      AuctionCommandSender.CommandResult result, AuctionLiveTracker tracker,
      PeerFrameConsole console) {
    if (AuctionCommandParser.isBidCommand(typedCommand) && tracker.hasActiveAuction()) {
      handleBidResponse(result, tracker);
      return;
    }
    if (!AuctionCommandParser.isGetDetailsCommand(typedCommand)) {
      if (tracker.hasActiveAuction()) {
        tracker.cancelByUserCommand();
      }
      return;
    }
    handleGetDetailsResponse(result, tracker, console);
  }

  private static void handleBidResponse(AuctionCommandSender.CommandResult result,
      AuctionLiveTracker tracker) {
    if (result == null || result.response() == null) {
      return;
    }
    ResponseMessage resp = result.response();
    if (resp.kind() == ResponseKind.OK && resp.okCode() == OkCode.BID_ACCEPTED) {
      String acceptedAmount = AuctionCommandSender.field(resp, 1);
      tracker.showTemporaryStatus("bid submitted: " + acceptedAmount, 5_000);
      tracker.refreshNow();
    }
  }

  private static void handleGetDetailsResponse(AuctionCommandSender.CommandResult result,
      AuctionLiveTracker tracker, PeerFrameConsole console) {
    if (result == null || result.response() == null) {
      return;
    }
    ResponseMessage resp = result.response();
    if (resp.kind() == ResponseKind.OK && resp.okCode() == OkCode.CURRENT_AUCTION) {
      startOrRefreshTracker(resp, result.roundTripMs(), tracker);
      return;
    }
    if (resp.kind() == ResponseKind.OK && resp.okCode() == OkCode.NO_AUCTION
        && tracker.hasActiveAuction()) {
      tracker.markAuctionCancelledAbruptly();
      console.addWarn("AUCTION_CANCELLED\n- reason: server reported no active auction");
    }
  }

  private static void startOrRefreshTracker(ResponseMessage resp, long roundTripMs,
      AuctionLiveTracker tracker) {
    AuctionProtocolViews.CurrentAuctionView view = AuctionProtocolViews.currentAuction(resp,
        roundTripMs);
    tracker.startOrRefresh(
        view.objectId(),
        view.description(),
        view.seller(),
        view.highestBid(),
        view.highestBidder(),
        view.remainingSec(),
        view.offsetMs()
    );
  }

  private static boolean shouldSuppressStandardResponse(String typedCommand,
      AuctionCommandSender.CommandResult result, AuctionLiveTracker tracker) {
    if (!AuctionCommandParser.isBidCommand(typedCommand) || !tracker.hasActiveAuction()
        || result == null || result.response() == null) {
      return false;
    }
    ResponseMessage resp = result.response();
    // tracker shows this already
    return resp.kind() == ResponseKind.OK && resp.okCode() == OkCode.BID_ACCEPTED;
  }

  private static void displayRawResponse(PeerFrameConsole console, String raw) {
    try {
      displayResponse(console, ResponseMessage.parse(raw));
    } catch (Exception ignored) {
      console.addServer(PeerConsoleFormatter.formatRaw(raw));
    }
  }

  private static void displayResponse(PeerFrameConsole console, ResponseMessage response) {
    String formatted = PeerConsoleFormatter.format(response);
    if (response != null && response.kind() == ResponseKind.ERR) {
      console.addError(formatted);
      return;
    }
    if (isCurrentAuctionSnapshot(response)) {
      console.addAuction(formatted);
      return;
    }
    console.addServer(formatted);
  }

  private static boolean isCurrentAuctionSnapshot(ResponseMessage response) {
    return response != null && response.kind() == ResponseKind.OK
        && response.okCode() == OkCode.CURRENT_AUCTION && response.fields() != null
        && response.fields().length >= 6;
  }
}
