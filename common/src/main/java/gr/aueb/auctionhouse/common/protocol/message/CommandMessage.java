package gr.aueb.auctionhouse.common.protocol.message;

import gr.aueb.auctionhouse.common.protocol.codec.WireArgTokenizer;
import gr.aueb.auctionhouse.common.protocol.codec.WireLineParser;
import gr.aueb.auctionhouse.common.protocol.enums.CommandType;
import gr.aueb.auctionhouse.common.protocol.message.interfaces.RequestProtocolMessage;

import java.util.Arrays;

public record CommandMessage(
        String raw,
        CommandType command,
        String[] args,
        String requestId,
        String rawCommand
) implements RequestProtocolMessage {

    private static final String[] EMPTY_ARGS = new String[0];

    public static CommandMessage parse(String line) {
        WireLineParser.ParsedLine parsed = WireLineParser.parse(line, false);
        String[] tokens = normalizeCommandTokens(parsed.tokens());

        String rawCommand = extractRawCommand(tokens);
        CommandType command = CommandType.fromWire(rawCommand);
        String[] args = extractArgs(tokens);

        return new CommandMessage(parsed.raw(), command, args, parsed.requestId(), rawCommand);
    }

    @Override
    public String formatResponse(String response) {
        if (requestId == null || requestId.isBlank()) {
            return response;
        }
        return requestId + "#" + response;
    }

    private static String extractRawCommand(String[] tokens) {
        return tokens[0].toUpperCase();
    }

    private static String[] extractArgs(String[] tokens) {
        if (tokens.length <= 1) {
            return EMPTY_ARGS;
        }
        return Arrays.copyOfRange(tokens, 1, tokens.length);
    }

    private static String[] normalizeCommandTokens(String[] parsedTokens) {
        if (parsedTokens.length != 1) {
            return parsedTokens;
        }
        String only = parsedTokens[0];
        if (only == null) {
            return parsedTokens;
        }
        String trimmed = only.trim();

        String[] spaceTokens = WireArgTokenizer.splitWhitespacePreservingQuotes(trimmed);
        return spaceTokens.length == 0 ? parsedTokens : spaceTokens;
    }
}
