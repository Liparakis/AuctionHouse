package gr.aueb.auctionhouse.peer.core.utils;

import gr.aueb.auctionhouse.common.protocol.builder.CommandWire;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;
import gr.aueb.auctionhouse.peer.connection.PeerConnection;
import gr.aueb.auctionhouse.peer.core.AuctionCommandSender;
import gr.aueb.auctionhouse.peer.transaction.PeerTransactionClient;

import java.nio.file.Path;
import java.util.Random;
import java.util.logging.Logger;

// handles txn ready event
public final class PeerTransactionHandler {

  private static final Logger LOG = Logger.getLogger(PeerTransactionHandler.class.getName());
  private static final double PROCEED_TRANSACTION_PROBABILITY = 0.70;
  private static final Random RANDOM = new Random();

  private PeerTransactionHandler() {
  }

  // does one transaction event
  public static void handle(String logPrefix, ResponseMessage event, String token, Path sharedRoot,
      PeerConnection connection, long timeoutMs, java.util.function.Consumer<String> onSuccess,
      java.util.function.Consumer<String> onFailure) {
    String objectId = AuctionCommandSender.field(event, 0);
    String sellerToken = AuctionCommandSender.field(event, 1);
    String winnerToken = AuctionCommandSender.field(event, 2);
    String sellerIp = AuctionCommandSender.field(event, 3);
    int sellerPort = AuctionCommandSender.parseInt(AuctionCommandSender.field(event, 4));

    if (!isValidTransactionEvent(objectId, sellerToken, winnerToken)) {
      return;
    }
    // only winner does this
    if (!token.equals(winnerToken) || token.equals(sellerToken)) {
      return;
    }

    if (RANDOM.nextDouble() > PROCEED_TRANSACTION_PROBABILITY) {
      LOG.warning(logPrefix + " TXN cancelled by winner object=" + objectId);
      AuctionCommandSender.sendExpect(connection,
          CommandWire.transactionFailed(objectId), timeoutMs,
          AuctionCommandSender.ok(OkCode.TRANSACTION_RECORDED), AuctionCommandSender.errAny());
      if (onFailure != null) {
        onFailure.accept("TRANSACTION_CANCELLED\n- object: " + objectId);
      }
      return;
    }

    boolean completed = PeerTransactionClient.execute(objectId, sellerIp, sellerPort, token,
        sharedRoot);
    if (!completed) {
      LOG.warning(
          logPrefix + " TXN failed object=" + objectId + " seller=" + sellerIp + ":" + sellerPort);
      AuctionCommandSender.sendExpect(connection,
          CommandWire.transactionFailed(objectId), timeoutMs,
          AuctionCommandSender.ok(OkCode.TRANSACTION_RECORDED), AuctionCommandSender.errAny());
      if (onFailure != null) {
        onFailure.accept(
            "TRANSACTION_FAILED\n- object: " + objectId + "\n- seller: " + sellerIp + ":"
                + sellerPort);
      }
      return;
    }

    ResponseMessage ack = AuctionCommandSender.sendExpect(connection,
        CommandWire.transaction(objectId), timeoutMs,
        AuctionCommandSender.ok(OkCode.TRANSACTION_RECORDED), AuctionCommandSender.errAny());

    if (ack != null && ack.kind() == ResponseKind.OK
        && ack.okCode() == OkCode.TRANSACTION_RECORDED) {
      LOG.info(logPrefix + " TXN complete recorded for object=" + objectId);
      if (onSuccess != null) {
        onSuccess.accept(objectId);
      }
    } else {
      LOG.warning(logPrefix + " TRANSACTION_COMPLETE failed for object=" + objectId + " response="
          + AuctionCommandSender.rawOf(ack));
      if (onFailure != null) {
        onFailure.accept("TRANSACTION_COMPLETE_NOTIFY_FAILED\n- object: " + objectId);
      }
    }
  }

  private static boolean isValidTransactionEvent(String objectId, String sellerToken,
      String winnerToken) {
    return objectId != null && !objectId.isBlank() && sellerToken != null && !sellerToken.isBlank()
        && winnerToken != null && !winnerToken.isBlank();
  }
}
