package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;
import gr.aueb.auctionhouse.peer.connection.PeerConnection;

import java.util.function.Predicate;

public final class AuctionCommandSender {

  private AuctionCommandSender() {
  }

  public static CommandResult send(PeerConnection connection, String command, long timeoutMs) {
    String requestId = connection.sendWithRequest(command);
    long startNs = System.nanoTime();
    ResponseMessage response = connection.readResponseForRequest(requestId, timeoutMs);
    long rttMs = (System.nanoTime() - startNs) / 1_000_000L;
    return response == null ? null : new CommandResult(response, Math.max(0, rttMs));
  }

  @SafeVarargs
  public static ResponseMessage sendExpect(PeerConnection connection, String command,
      long timeoutMs, Predicate<ResponseMessage>... matchers) {
    String requestId = connection.sendWithRequest(command);
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      long remaining = Math.max(1, deadline - System.currentTimeMillis());
      ResponseMessage response = connection.readResponseForRequest(requestId, remaining);
      if (response == null) {
        return null;
      }
      for (Predicate<ResponseMessage> matcher : matchers) {
        if (matcher.test(response)) {
          return response;
        }
      }
    }
    return null;
  }

  public static Predicate<ResponseMessage> ok(OkCode code) {
    return response -> response.kind() == ResponseKind.OK && response.okCode() == code;
  }

  public static Predicate<ResponseMessage> errAny() {
    return response -> response.kind() == ResponseKind.ERR;
  }

  public static void requireOk(ResponseMessage response, OkCode expected, String context) {
    if (response == null || response.kind() != ResponseKind.OK || response.okCode() != expected) {
      throw new IllegalStateException(context + ": " + rawOf(response));
    }
  }

  public static String field(ResponseMessage response, int idx) {
    String[] fields = response.fields();
    return (idx >= 0 && idx < fields.length) ? fields[idx] : "";
  }

  public static double parseDouble(String value) {
    try {
      return Double.parseDouble(value);
    } catch (Exception ignored) {
      return 0;
    }
  }

  public static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (Exception ignored) {
      return 0;
    }
  }

  public static int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (Exception ignored) {
      return -1;
    }
  }

  public static String rawOf(ResponseMessage response) {
    return response == null ? "" : response.raw();
  }

  public record CommandResult(ResponseMessage response, long roundTripMs) {

  }
}
