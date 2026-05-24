package gr.aueb.auctionhouse.common.protocol.message;

import gr.aueb.auctionhouse.common.protocol.codec.WireLineParser;
import gr.aueb.auctionhouse.common.protocol.enums.ErrorCode;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.interfaces.ResponseProtocolMessage;

import java.util.Arrays;
import java.util.Locale;

public record ResponseMessage(String raw, String requestId, ResponseKind kind, OkCode okCode, ErrorCode errorCode,
                              EventType eventType, String[] fields) implements ResponseProtocolMessage {

    private static final String[] EMPTY_FIELDS = new String[0];

    public static ResponseMessage parse(String line) {
        WireLineParser.ParsedLine parsed = WireLineParser.parse(line, true);
        String[] tokens = parsed.tokens();

        if (tokens.length == 0) {
            throw new IllegalArgumentException("Malformed wire line");
        }

        String head = tokens[0].toUpperCase(Locale.ROOT);
        String code = tokens.length > 1 ? tokens[1] : null;

        ResponseKind kind = ResponseKind.UNKNOWN;
        OkCode okCode = OkCode.UNKNOWN;
        ErrorCode errorCode = ErrorCode.UNKNOWN;
        EventType eventType = EventType.UNKNOWN;
        int fieldStart = 1;

        switch (head) {
            case "OK" -> {
                kind = ResponseKind.OK;
                okCode = OkCode.fromWire(code);
                fieldStart = 2;
            }
            case "ERR" -> {
                kind = ResponseKind.ERR;
                errorCode = ErrorCode.fromWire(code);
                fieldStart = 2;
            }
            case "EVENT" -> {
                kind = ResponseKind.EVENT;
                eventType = EventType.fromWire(code);
                fieldStart = 2;
            }
        }

        return new ResponseMessage(
                parsed.raw(),
                parsed.requestId(),
                kind,
                okCode,
                errorCode,
                eventType,
                fieldsFrom(tokens, fieldStart)
        );
    }

    private static String[] fieldsFrom(String[] tokens, int from) {
        if (tokens.length <= from) {
            return EMPTY_FIELDS;
        }
        return Arrays.copyOfRange(tokens, from, tokens.length);
    }
}
