package gr.aueb.auctionhouse.server.config;


public final class ServerHelpCatalog {

  /**
   * Ordered list of supported command signatures.
   */
  public static final String[] GUEST_COMMAND_LINES = {
      "PING",
      "HELLO",
      "REGISTER|username|password",
      "LOGIN|username|password"
  };

  public static final String[] AUTH_COMMAND_LINES = {
      "PING",
      "HELLO",
      "REGISTER|username|password",
      "LOGIN|username|password",
      "LOGOUT",
      "SEND_AUCTION_REQUEST|description|startPrice|durationSec",
      "SEND_AUCTION_REQUEST|--meta|objectId",
      "GET_CURRENT_AUCTION",
      "BID|objectId|amount",
      "GET_USER_STATS|[username]",
      "GET_DETAILS|objectId"
  };

  public static String[] commandLinesFor(boolean authenticated) {
    return authenticated ? AUTH_COMMAND_LINES : GUEST_COMMAND_LINES;
  }

  private ServerHelpCatalog() {}
}
