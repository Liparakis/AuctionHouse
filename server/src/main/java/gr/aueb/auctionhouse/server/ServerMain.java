package gr.aueb.auctionhouse.server;

import gr.aueb.auctionhouse.server.config.ServerValidation;
import gr.aueb.auctionhouse.server.core.AuctionServer;


public class ServerMain {

  static void main(String[] args) {
    int port = ServerValidation.DEFAULT_SERVER_PORT;
    String bindIp = "0.0.0.0";
    if (args.length >= 1) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException ex) {
        System.err.println(
            "[SERVER] Invalid port argument '" + args[0] + "' must be an integer.");
        System.exit(1);
      }
      if (port < ServerValidation.MIN_TCP_PORT || port > ServerValidation.MAX_TCP_PORT) {
        System.err.println("[SERVER] Port " + port + " out of range (1..65535).");
        System.exit(1);
      }
    }
    if (args.length >= 2 && args[1] != null && !args[1].isBlank()) {
      bindIp = args[1].trim();
    }

    AuctionServer server = new AuctionServer(port, bindIp);
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "auction-server-shutdown"));
    server.start();
  }
}
