package gr.aueb.auctionhouse.common.model;

import java.time.Instant;
import java.util.UUID;

public record AuctionRequest(
        String objectId,
        String sellerToken,
        String sellerIp,
        int sellerPort,
        String description,
        double startingPrice,
        int durationSeconds,
        Instant requestedAt
) {
    public static AuctionRequest newRequest(
            String sellerToken,
            String sellerIp,
            int sellerPort,
            String description,
            double startingPrice,
            int durationSeconds
    ) {
        String objectId = UUID.randomUUID().toString();
        return new AuctionRequest(
                objectId,
                sellerToken,
                sellerIp,
                sellerPort,
                normalizedDescription(description, objectId),
                startingPrice,
                durationSeconds,
                Instant.now()
        );
    }

    private static String normalizedDescription(String description, String objectId) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        //Construct a default description if none is provided.
        String shortId = objectId == null ? "item" : objectId.substring(0, Math.min(8, objectId.length()));
        return "Auction item " + shortId;
    }
}
