package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.builder.CommandWire;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;
import gr.aueb.auctionhouse.peer.connection.PeerConnection;
import gr.aueb.auctionhouse.peer.core.utils.PeerSessionSupport;
import gr.aueb.auctionhouse.peer.transaction.ObjectMetadataStore;
import gr.aueb.auctionhouse.peer.transaction.PeerTransactionServer;
import gr.aueb.auctionhouse.peer.ui.PeerFrameConsole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

// does extra stuff after commands
final class AuctionSessionAutomator {

  private static final Logger LOG = Logger.getLogger(AuctionSessionAutomator.class.getName());

  private AuctionSessionAutomator() {
  }

  // makes pending meta file
  static PendingAuctionFile maybePreparePendingAuction(String command, Path sharedRoot,
      PeerFrameConsole console) {
    if (AuctionCommandParser.isNotSendAuctionRequestCommand(command)) {
      return null;
    }
    if (sharedRoot == null) {
      return null;
    }
    String[] args = AuctionCommandParser.parseCommandArgs(command);
    try {
      String metaObjectId = AuctionCommandParser.metaObjectId(args);
      if (metaObjectId != null) {
        Path source = ObjectMetadataStore.metadataPath(sharedRoot, metaObjectId);
        if (!Files.exists(source)) {
          throw new IOException("meta file not found for objectId=" + metaObjectId);
        }
        return new PendingAuctionFile(source, false);
      }

      AuctionCommandParser.AuctionRequestSpec spec = AuctionCommandParser.resolveAuctionRequestSpec(
          args, sharedRoot);
      Path pending = ObjectMetadataStore.createPending(sharedRoot, spec.description(),
          spec.startPrice(), spec.durationSec());
      return new PendingAuctionFile(pending, true);
    } catch (Exception ex) {
      if (console != null) {
        console.addWarn("metadata_prepare_failed\n- reason: " + ex.getMessage());
      }
      LOG.warning("[PEER] metadata_prepare_failed: " + ex.getMessage());
      return null;
    }
  }

  // finishes pending meta file
  static void finalizePendingAuction(PendingAuctionFile pendingAuction, Path sharedRoot,
      ResponseMessage response, PeerFrameConsole console) {
    if (pendingAuction == null || sharedRoot == null || response == null) {
      return;
    }
    try {
      if (response.kind() == ResponseKind.OK && response.okCode() == OkCode.AUCTION_ENQUEUED) {
        String objectId = AuctionCommandSender.field(response, 0);
        Path finalPath = ObjectMetadataStore.bindPendingToObject(pendingAuction.pendingPath(),
            sharedRoot, objectId);
        console.addInfo(
            "metadata_ready\n- object: " + objectId + "\n- file: " + finalPath.toAbsolutePath());
        return;
      }
      if (pendingAuction.deleteOnFailure()) {
        Files.deleteIfExists(pendingAuction.pendingPath());
      }
    } catch (Exception ex) {
      console.addWarn("metadata_finalize_failed\n- reason: " + ex.getMessage());
    }
  }

  // handles login/logout stuff
  static void automateInteractiveSession(String typedCommand, PeerConnection connection,
      ResponseMessage response, AtomicReference<String> tokenRef,
      AtomicReference<PeerTransactionServer> txnServerRef, AtomicReference<Path> sharedRootRef,
      PeerFrameConsole console, long defaultTimeoutMs) {
    if (response == null) {
      return;
    }
    if (isSuccessfulLogin(response, typedCommand)) {
      handleLoginSuccess(connection, response, typedCommand, tokenRef, txnServerRef, sharedRootRef,
          console, defaultTimeoutMs);
      return;
    }
    if (isSuccessfulLogout(response, typedCommand)) {
      tokenRef.set(null);
      closeInteractiveTxnServer(txnServerRef);
    }
  }

  static void closeInteractiveTxnServer(AtomicReference<PeerTransactionServer> txnServerRef) {
    PeerTransactionServer server = txnServerRef.getAndSet(null);
    if (server == null) {
      return;
    }
    try {
      server.close();
    } catch (Exception ignored) {
    }
  }

  private static boolean isSuccessfulLogin(ResponseMessage response, String command) {
    return response.kind() == ResponseKind.OK && response.okCode() == OkCode.TOKEN
        && AuctionCommandParser.isLoginCommand(command);
  }

  private static boolean isSuccessfulLogout(ResponseMessage response, String command) {
    return response.kind() == ResponseKind.OK && response.okCode() == OkCode.LOGGED_OUT
        && AuctionCommandParser.isLogoutCommand(command);
  }

  private static void handleLoginSuccess(PeerConnection connection, ResponseMessage response,
      String typedCommand, AtomicReference<String> tokenRef,
      AtomicReference<PeerTransactionServer> txnServerRef, AtomicReference<Path> sharedRootRef,
      PeerFrameConsole console, long defaultTimeoutMs) {
    String token = AuctionCommandSender.field(response, 0);
    tokenRef.set(token);
    closeInteractiveTxnServer(txnServerRef);

    String username = AuctionCommandParser.extractLoginUsername(typedCommand);
    try {
      Path root = resolveRootForUser(username, sharedRootRef);
      PeerTransactionServer txnServer = new PeerTransactionServer(0, token, root);
      txnServer.start();

      ResponseMessage listenResp = AuctionCommandSender.sendExpect(connection,
          peerListenWire(connection, txnServer.listenPort()), defaultTimeoutMs,
          AuctionCommandSender.ok(OkCode.PEER_REGISTERED), AuctionCommandSender.errAny());

      if (listenResp != null && listenResp.kind() == ResponseKind.OK
          && listenResp.okCode() == OkCode.PEER_REGISTERED) {
        txnServerRef.set(txnServer);
      } else {
        txnServer.close();
        console.addWarn("auto_peer_registration_failed");
      }
    } catch (Exception ex) {
      closeInteractiveTxnServer(txnServerRef);
      console.addWarn("auto_peer_listener_failed\n- reason: " + ex.getMessage());
    }
  }

  private static Path resolveRootForUser(String username, AtomicReference<Path> sharedRootRef)
      throws IOException {
    if (username == null || username.isBlank()) {
      Path root = ObjectMetadataStore.peerRoot("interactive-" + ProcessHandle.current().pid());
      sharedRootRef.set(root);
      return root;
    }
    Path root = ObjectMetadataStore.peerRoot(username.trim().toLowerCase(Locale.ROOT));
    sharedRootRef.set(root);
    return root;
  }

  private static String peerListenWire(PeerConnection connection, int listenPort) {
    String listenIp = PeerSessionSupport.advertisedListenIp(connection);
    return listenIp == null
        ? CommandWire.peerListen(listenPort)
        : CommandWire.peerListen(listenIp, listenPort);
  }

  record PendingAuctionFile(Path pendingPath, boolean deleteOnFailure) {

  }
}
