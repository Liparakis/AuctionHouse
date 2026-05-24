package gr.aueb.auctionhouse.server.config;

public final class ServerValidation {

  public static final int MIN_TCP_PORT = 1;
  public static final int MAX_TCP_PORT = 65_535;
  public static final int DEFAULT_SERVER_PORT = 5050;
  public static final long AUCTION_TICK_MS = 200L;
  public static final long SESSION_TOKEN_TTL_MS = 30L * 60L * 1000L;
  public static final int BROADCAST_WORKERS = 4;
  public static final int BROADCAST_QUEUE_CAPACITY = 2048;

  private ServerValidation() {
    // Constants Class
  }
}
