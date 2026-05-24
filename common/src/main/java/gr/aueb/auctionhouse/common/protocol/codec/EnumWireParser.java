package gr.aueb.auctionhouse.common.protocol.codec;

import java.util.Locale;

public final class EnumWireParser {

    private EnumWireParser() {
    }

    public static <E extends Enum<E>> E parse(String value, Class<E> enumType, E unknown) {
        if (value == null || value.isEmpty()) {
            return unknown;
        }
        String normalized;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        //Trim only if needed
        if (first <= ' ' || last <= ' ') {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return unknown;
            }
            normalized = trimmed.toUpperCase(Locale.ROOT);
        } else {
            normalized = value.toUpperCase(Locale.ROOT);
        }
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException ignored) {
            /* Ignored */
            return unknown;
        }
    }
}
