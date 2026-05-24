package gr.aueb.auctionhouse.common.protocol.codec;

public final class WireLineParser {

    private WireLineParser() {
    }

    public static ParsedLine parse(String line, boolean keepTrailingEmptyFields) {
        String trimmed = validate(line);
        int marker = trimmed.indexOf('#');
        String requestId = null;
        String payload = trimmed;

        if (marker > 0) {
            // Format ID#MESSAGE
            String candidateId = trimmed.substring(0, marker).trim();
            String candidatePayload = trimmed.substring(marker + 1).trim();
            if (!candidateId.isEmpty() && !candidatePayload.isEmpty()) {
                requestId = candidateId;
                payload = candidatePayload;
            }
        }

        String[] tokens = WireTokenizer.splitPipe(payload, keepTrailingEmptyFields);
        return new ParsedLine(trimmed, requestId, tokens);
    }

    private static String validate(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Input line is null");
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Input line is empty");
        }
        return trimmed;
    }

    public record ParsedLine(String raw, String requestId, String[] tokens) {
    }
}
