package gr.aueb.auctionhouse.common.protocol.codec;

import gr.aueb.auctionhouse.common.protocol.enums.ErrorCode;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;

public final class Protocol {

    static final char DELIMITER = '|';

    //Usually this capacity is enough.
    private static final int DEFAULT_JOIN_CAPACITY = 64;

    private Protocol() {
    }

    public static String ok(OkCode code, String... fields) {
        return join("OK", code.name(), fields);
    }

    public static String error(String code, String message) {
        return join("ERR", code, message);
    }

    public static String error(ErrorCode code, String message) {
        return error(code.name(), message);
    }

    public static String event(String eventType, String... fields) {
        return join("EVENT", eventType, fields);
    }

    public static String event(EventType eventType, String... fields) {
        return event(eventType.name(), fields);
    }

    public static String join(String... fields) {
        if (fields.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(DEFAULT_JOIN_CAPACITY);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(DELIMITER);
            }
            if (fields[i] != null) {
                sb.append(fields[i]);
            }
        }
        return sb.toString();
    }

    private static String join(String head, String code, String[] tail) {
        String[] fields = new String[tail.length + 2];
        fields[0] = head;
        fields[1] = code;
        System.arraycopy(tail, 0, fields, 2, tail.length);
        return join(fields);
    }
}
