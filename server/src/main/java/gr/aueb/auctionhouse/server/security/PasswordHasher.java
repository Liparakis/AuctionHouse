package gr.aueb.auctionhouse.server.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;


public final class PasswordHasher {

  private static final String ALGO = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 120_000;
  private static final int SALT_BYTES = 16;
  private static final int KEY_BITS = 256;

  private static final SecureRandom RNG = new SecureRandom();

  private PasswordHasher() {
  }

  public static String hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    RNG.nextBytes(salt);
    byte[] hash = derive(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
    return ITERATIONS + "$" + b64(salt) + "$" + b64(hash);
  }

  public static boolean verify(String password, String stored) {
    if (stored == null || stored.isBlank()) {
      return false;
    }
    String[] parts = stored.split("\\$");
    if (parts.length != 3) {
      return false;
    }
    try {
      int iterations = Integer.parseInt(parts[0]);
      byte[] salt = Base64.getDecoder().decode(parts[1]);
      byte[] expected = Base64.getDecoder().decode(parts[2]);
      byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
      return MessageDigest.isEqual(expected, actual);
    } catch (Exception ex) {
      return false;
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations, int keyBits) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
      return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to hash password", ex);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}