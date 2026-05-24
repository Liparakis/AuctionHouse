package gr.aueb.auctionhouse.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerHelpCatalogTest {

  @Test
  void authenticatedHelpIncludesMetaAuctionRequestVariant() {
    boolean found = false;
    for (String line : ServerHelpCatalog.AUTH_COMMAND_LINES) {
      if (line.contains("SEND_AUCTION_REQUEST|--meta|objectId")) {
        found = true;
        break;
      }
    }
    assertTrue(found);
  }
}
