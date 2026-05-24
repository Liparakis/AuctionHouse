package gr.aueb.auctionhouse.peer.core;

import java.io.IOException;

public class AuctionPeer {

  private final String host;
  private final int port;

  public AuctionPeer(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public void startInteractive() throws IOException {
    new AuctionInteractiveSession(host, port).run();
  }

  public void startAuto(String username, String password, int listenPort, double maxBid,
      long pollMs, Double initialAuctionStartPrice, Integer initialAuctionDurationSec) {
    new AuctionAutoSession(host, port).run(username, password, listenPort, maxBid,
        pollMs, initialAuctionStartPrice, initialAuctionDurationSec);
  }
}
