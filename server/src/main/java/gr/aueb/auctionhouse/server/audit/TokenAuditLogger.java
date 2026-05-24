package gr.aueb.auctionhouse.server.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

public final class TokenAuditLogger {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  });

  private TokenAuditLogger() {
  }

  public static void log(TokenAuditEvent event, String username, String token, String remote,
      String details) {

    System.out.println("[TOKEN_AUDIT] ts=" + Instant.now() + " event=" + event.name() + " user=" + (
        username == null ? "" : username) + " token_fp=" + fingerprint(token) + " remote=" + (
        remote == null ? "" : remote) + " details=" + (details == null ? "" : details));
  }

  private static String fingerprint(String token) {
    if (token == null || token.isBlank()) {
      return "";
    }
    String prefix = token.length() <= 6 ? token : token.substring(0, 6);
    return prefix + ":" + sha256Hex(token, 12);
  }

  @SuppressWarnings("SameParameterValue")
  private static String sha256Hex(String value, int maxChars) {
    MessageDigest md = DIGEST.get();
    md.reset();
    byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));

    int chars = Math.min(maxChars, hash.length * 2);
    char[] buf = new char[chars];
    for (int i = 0, c = 0; c < chars; i++) {
      buf[c++] = HEX[(hash[i] >> 4) & 0xF];
      if (c < chars) {
        buf[c++] = HEX[hash[i] & 0xF];
      }
    }
    return new String(buf);
  }
}