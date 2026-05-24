package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.builder.CommandWire;
import gr.aueb.auctionhouse.common.protocol.codec.WireArgTokenizer;
import gr.aueb.auctionhouse.peer.transaction.ObjectMetadataStore;
import gr.aueb.auctionhouse.peer.transaction.PeerTransactionServer;
import gr.aueb.auctionhouse.peer.ui.PeerFrameConsole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class AuctionCommandParser {

  // used when no desc is typed
  static final String DEFAULT_ITEM_DESCRIPTION = "Some item";

  private AuctionCommandParser() {
  }

  static String normalizeInteractiveCommand(String typedCommand, Path sharedRoot,
      PeerTransactionServer transactionServer, PeerFrameConsole console) {
    if (isNotSendAuctionRequestCommand(typedCommand)) {
      return typedCommand;
    }
    if (transactionServer == null) {
      console.addWarn(
          "send_auction_request requires login first (peer transaction listener not running)");
      return typedCommand;
    }
    String[] args = parseCommandArgs(typedCommand);
    try {
      AuctionRequestSpec spec = resolveAuctionRequestSpec(args, sharedRoot);
      return CommandWire.requestAuction(spec.description(), spec.startPrice(),
          spec.durationSec());
    } catch (Exception ex) {
      if (console != null) {
        console.addWarn("send_auction_request parse failed: " + ex.getMessage());
      }
      return typedCommand;
    }
  }

  static String extractLoginUsername(String typedCommand) {
    if (!isLoginCommand(typedCommand)) {
      return null;
    }
    String[] args = parseCommandArgs(typedCommand);
    return args.length >= 1 ? args[0] : null;
  }

  static boolean isNotSendAuctionRequestCommand(String command) {
    return !isCommand(command, "SEND_AUCTION_REQUEST")
        && !isCommand(command, "requestAuction")
        && !isCommand(command, "getAuctionRequest");
  }

  static boolean isLoginCommand(String command) {
    return isCommand(command, "LOGIN");
  }

  static boolean isLogoutCommand(String command) {
    return isCommand(command, "LOGOUT");
  }

  static boolean isGetDetailsCommand(String command) {
    return isCommand(command, "GET_DETAILS")
        || isCommand(command, "getAuctionDetails")
        || isCommand(command, "sendAuctionDetails");
  }

  static boolean isBidCommand(String command) {
    return isCommand(command, "BID") || isCommand(command, "placeBid");
  }

  // checks command name
  static boolean isCommand(String command, String expected) {
    if (command == null || command.isBlank()) {
      return false;
    }
    String trimmed = command.trim();
    String head = trimmed.contains("|") ? trimmed.substring(0, trimmed.indexOf('|'))
        : trimmed.split("\\s+")[0];
    return expected.equalsIgnoreCase(head);
  }

  // gets args after command
  static String[] parseCommandArgs(String command) {
    if (command == null || command.isBlank()) {
      return new String[0];
    }
    String trimmed = command.trim();
    if (trimmed.contains("|")) {
      String[] parts = trimmed.split("\\|", -1);
      return parts.length <= 1 ? new String[0] : Arrays.copyOfRange(parts, 1, parts.length);
    }
    String[] parts = WireArgTokenizer.splitWhitespacePreservingQuotes(trimmed);
    return parts.length <= 1 ? new String[0] : Arrays.copyOfRange(parts, 1, parts.length);
  }

  // makes auction args usable
  static AuctionRequestSpec resolveAuctionRequestSpec(String[] args, Path sharedRoot)
      throws IOException {
    if (isMetaSendAuctionRequest(args)) {
      return resolveFromMetadata(args[1], sharedRoot);
    }
    if (args.length == 2) {
      return resolveFromPriceAndDuration(DEFAULT_ITEM_DESCRIPTION, args[0], args[1]);
    }
    if (args.length == 3) {
      String description = args[0].isBlank() ? DEFAULT_ITEM_DESCRIPTION : args[0];
      return resolveFromPriceAndDuration(description, args[1], args[2]);
    }
    throw new IOException("use: SEND_AUCTION_REQUEST <start> <duration>"
        + " | SEND_AUCTION_REQUEST \"<desc>\" <start> <duration>"
        + " | SEND_AUCTION_REQUEST --meta <objectId>");
  }

  static boolean isMetaSendAuctionRequest(String[] args) {
    return args.length == 2 && ("meta".equalsIgnoreCase(args[0]) || "--meta".equalsIgnoreCase(
        args[0]));
  }

  static String metaObjectId(String[] args) {
    return isMetaSendAuctionRequest(args) ? args[1] : null;
  }

  private static AuctionRequestSpec resolveFromMetadata(String objectId, Path sharedRoot)
      throws IOException {
    Path source = ObjectMetadataStore.metadataPath(sharedRoot, objectId);
    if (!Files.exists(source)) {
      throw new IOException("meta file not found for objectId=" + objectId);
    }
    ObjectMetadataStore.MetadataFields fields = ObjectMetadataStore.readMetadataFields(source);
    return new AuctionRequestSpec(fields.description(), fields.startBid(), fields.durationSec());
  }

  private static AuctionRequestSpec resolveFromPriceAndDuration(String description, String rawPrice,
      String rawDuration) throws IOException {
    double startPrice;
    int durationSec;
    try {
      startPrice = Double.parseDouble(rawPrice);
      durationSec = Integer.parseInt(rawDuration);
    } catch (NumberFormatException ex) {
      throw new IOException("invalid number format: " + ex.getMessage(), ex);
    }
    if (startPrice <= 0 || durationSec <= 0) {
      throw new IOException("startPrice and durationSec must be > 0");
    }
    return new AuctionRequestSpec(description, startPrice, durationSec);
  }

  record AuctionRequestSpec(String description, double startPrice, int durationSec) {

  }
}
