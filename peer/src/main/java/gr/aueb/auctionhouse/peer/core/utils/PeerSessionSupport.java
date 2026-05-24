package gr.aueb.auctionhouse.peer.core.utils;

import gr.aueb.auctionhouse.peer.connection.PeerConnection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

// shared peer helpers
public final class PeerSessionSupport {

  private static final long RECONNECT_BASE_MS = 1_000;
  private static final long RECONNECT_MAX_MS = 15_000;

  private PeerSessionSupport() {
  }

  public static PeerConnection openConnection(String host, int port) throws IOException {
    return new PeerConnection(new Socket(host, port));
  }

  // gets reconnect wait
  public static long reconnectBackoffMs(int attempt) {
    return Math.min(RECONNECT_BASE_MS << Math.min(attempt, 6), RECONNECT_MAX_MS);
  }

  // sleeps quietly
  public static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  // gets listen ip
  public static String advertisedListenIp(PeerConnection connection) {
    String ip = System.getProperty("auction.peer.listen.ip");
    if (ip != null && !ip.isBlank()) {
      return ip.trim();
    }
    if (connection == null) {
      return null;
    }
    InetAddress localAddress = connection.localAddress();
    if (localAddress == null || localAddress.isAnyLocalAddress()
        || localAddress.isLoopbackAddress()) {
      return null;
    }
    return localAddress.getHostAddress();
  }
}
