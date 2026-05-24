package gr.aueb.auctionhouse.common.protocol.codec;

import java.util.ArrayList;

final class WireTokenizer {

    private static final char DELIMITER = '|';
    private static final int EXPECTED_FIELDS = 8;

    private WireTokenizer() {
    }

    static String[] splitPipe(String payload, boolean keepTrailingEmptyFields) {
        ArrayList<String> tokens = new ArrayList<>(EXPECTED_FIELDS);
        int start = 0;
        final int len = payload.length();

        for (int i = 0; i < len; i++) {
            if (payload.charAt(i) == DELIMITER) {
                tokens.add(payload.substring(start, i));
                start = i + 1;
            }
        }
        tokens.add(payload.substring(start));

        if (keepTrailingEmptyFields) {
            return tokens.toArray(new String[0]);
        }
        return trimTrailingEmpty(tokens);
    }

    private static String[] trimTrailingEmpty(ArrayList<String> tokens) {
        int end = tokens.size();
        while (end > 0 && tokens.get(end - 1).isEmpty()) {
            end--;
        }
        return tokens.subList(0, end).toArray(new String[end]);
    }
}
