package gr.aueb.auctionhouse.peer;

import gr.aueb.auctionhouse.peer.config.PeerCliDefaults;
import gr.aueb.auctionhouse.peer.core.AuctionPeer;


public class PeerMain {

  private static final String[] AUTO_DEFAULT_NAMES = {
      "Panagiotis",
      "Kostas",
      "Dimitris",
      "Giwrgos",
      "Nikos",
      "Giannis",
      "Vasilis",
      "Christos",
      "Thanasis",
      "Stavros",
      "Spyros",
      "Manolis",
      "Michalis",
      "Petros",
      "Alexandros",
      "Andreas",
      "Leonidas",
      "Sotiris",
      "Theodoros",
      "Aggelos",
      "Maria",
      "Eleni",
      "Katerina",
      "Sofia",
      "Georgia",
      "Dimitra",
      "Ioanna",
      "Despoina",
      "Vasiliki",
      "Anna",
      "Christina",
      "Natalia",
      "Alexandra",
      "Eva",
      "Irini"
  };

  static void main(String[] args) throws Exception {
    if (args.length >= 1 && PeerCliDefaults.MODE_AUTO.equalsIgnoreCase(args[0])) {
      launchAutoMode(args);
    } else {
      launchInteractiveMode(args);
    }
  }

  private static void launchAutoMode(String[] args) {
    String host = argAt(args, 1, PeerCliDefaults.DEFAULT_HOST);
    int port = intArgAt(args, 2, PeerCliDefaults.DEFAULT_SERVER_PORT);
    int listenPort = intArgAt(args, 5, PeerCliDefaults.DEFAULT_LISTEN_PORT);
    String username = argAt(args, 3, defaultAutoUsername(listenPort));
    String password = argAt(args, 4, PeerCliDefaults.DEFAULT_PASSWORD);
    double maxBid = args.length > 6 ? Double.parseDouble(args[6]) : PeerCliDefaults.DEFAULT_MAX_BID;
    long pollMs = args.length > 7 ? Long.parseLong(args[7]) : PeerCliDefaults.DEFAULT_POLL_MS;
    Double startPrice = args.length >= 9 ? Double.parseDouble(args[8]) : null;
    Integer durationSec = args.length >= 10 ? Integer.parseInt(args[9]) : null;

    new AuctionPeer(host, port).startAuto(username, password, listenPort, maxBid, pollMs,
        startPrice, durationSec);
  }

  private static void launchInteractiveMode(String[] args) throws Exception {
    String host = argAt(args, 0, PeerCliDefaults.DEFAULT_HOST);
    int port = intArgAt(args, 1, PeerCliDefaults.DEFAULT_SERVER_PORT);
    new AuctionPeer(host, port).startInteractive();
  }

  private static String argAt(String[] args, int idx, String fallback) {
    return args.length > idx ? args[idx] : fallback;
  }

  private static int intArgAt(String[] args, int idx, int fallback) {
    return args.length > idx ? Integer.parseInt(args[idx]) : fallback;
  }

  private static String defaultAutoUsername(int listenPort) {
    return AUTO_DEFAULT_NAMES[Math.floorMod(listenPort, AUTO_DEFAULT_NAMES.length)];
  }
}
