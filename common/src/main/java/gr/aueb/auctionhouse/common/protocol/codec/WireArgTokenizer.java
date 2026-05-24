package gr.aueb.auctionhouse.common.protocol.codec;

import java.util.ArrayList;

public final class WireArgTokenizer {

    private WireArgTokenizer() {
    }

    public static String[] splitWhitespacePreservingQuotes(String input) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '"') {
                //Openining / Closing quotes
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes && Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    //End
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }
}
