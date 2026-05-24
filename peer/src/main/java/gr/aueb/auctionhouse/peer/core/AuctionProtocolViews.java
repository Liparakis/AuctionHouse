package gr.aueb.auctionhouse.peer.core;

import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;

import java.util.ArrayList;
import java.util.List;

final class AuctionProtocolViews {

  private AuctionProtocolViews() {
  }

  static List<ActiveAuctionSummaryView> activeAuctions(ResponseMessage response) {
    int count = AuctionCommandSender.parseInt(AuctionCommandSender.field(response, 0));
    List<ActiveAuctionSummaryView> auctions = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int base = 1 + (i * 5);
      auctions.add(new ActiveAuctionSummaryView(
          AuctionCommandSender.field(response, base),
          AuctionCommandSender.field(response, base + 1),
          AuctionCommandSender.field(response, base + 2),
          AuctionCommandSender.parseLong(AuctionCommandSender.field(response, base + 3)),
          AuctionCommandSender.parseInt(AuctionCommandSender.field(response, base + 4))
      ));
    }
    return auctions;
  }

  static CurrentAuctionView currentAuction(ResponseMessage response, long roundTripMs) {
    return new CurrentAuctionView(
        AuctionCommandSender.field(response, 0),
        AuctionCommandSender.field(response, 1),
        AuctionCommandSender.field(response, 2),
        AuctionCommandSender.field(response, 3),
        AuctionCommandSender.field(response, 4),
        AuctionCommandSender.parseLong(AuctionCommandSender.field(response, 5)),
        AuctionCommandSender.parseInt(AuctionCommandSender.field(response, 6)),
        roundTripMs / 2L
    );
  }

  static AuctionEndedView auctionEnded(ResponseMessage event) {
    return new AuctionEndedView(
        AuctionCommandSender.field(event, 0),
        AuctionCommandSender.field(event, 1),
        AuctionCommandSender.field(event, 2),
        AuctionCommandSender.field(event, 3),
        AuctionCommandSender.field(event, 4)
    );
  }

  static AuctionCancelledView auctionCancelled(ResponseMessage event) {
    return new AuctionCancelledView(
        AuctionCommandSender.field(event, 0),
        AuctionCommandSender.field(event, 1),
        AuctionCommandSender.field(event, 2),
        AuctionCommandSender.field(event, 3)
    );
  }

  static String blankOr(String value, String fallback) {
    return (value == null || value.isBlank()) ? fallback : value;
  }

  record ActiveAuctionSummaryView(String objectId,
                                  String description,
                                  String highestBid,
                                  long remainingSec,
                                  int durationSec) {
  }

  record CurrentAuctionView(String objectId,
                            String description,
                            String seller,
                            String highestBid,
                            String highestBidder,
                            long remainingSec,
                            int durationSec,
                            long offsetMs) {

  }

  record AuctionEndedView(String objectId,
                          String seller,
                          String winner,
                          String finalBid,
                          String status) {

  }

  record AuctionCancelledView(String objectId,
                              String disconnected,
                              String role,
                              String reason) {

  }
}
