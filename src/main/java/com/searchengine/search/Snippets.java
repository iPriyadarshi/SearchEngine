package com.searchengine.search;

import java.util.Locale;

/**
 * Picks a short, query-relevant window of a document's text for display:
 * centred on the first occurrence of any query word, falling back to the
 * beginning of the text.
 */
public final class Snippets {

    static final int LENGTH = 160;

    private static final int LEAD = 40;

    private Snippets() {
    }

    public static String around(String text, String query) {

        if (text == null || text.isEmpty()) {

            return "";
        }

        String flat = text.replaceAll("\\s+", " ").strip();

        String lower = flat.toLowerCase(Locale.ROOT);

        int first = -1;

        for (String word : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {

            if (word.length() < 3) {

                continue;
            }

            int at = lower.indexOf(word);

            if (at >= 0 && (first < 0 || at < first)) {

                first = at;
            }
        }

        int start = 0;

        if (first > LEAD) {

            // Begin at a word boundary a little before the match.
            int space = flat.lastIndexOf(' ', first - LEAD);

            start = space < 0 ? 0 : space + 1;
        }

        int end = Math.min(flat.length(), start + LENGTH);

        if (end < flat.length()) {

            int space = flat.lastIndexOf(' ', end);

            if (space > start) {

                end = space;
            }
        }

        return (start > 0 ? "..." : "") + flat.substring(start, end) + (end < flat.length() ? "..." : "");
    }
}
