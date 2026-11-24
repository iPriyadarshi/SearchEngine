package com.searchengine.index.disk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects, while indexing, the data behind spelling correction and
 * autocomplete, and writes it next to the index.
 * <ul>
 * <li><b>{@value IndexFiles#VOCABULARY}</b>: surface words (unstemmed,
 * lower-case) from titles and leading text, with the number of documents
 * using each. Words used by fewer than {@value #MIN_WORD_DOCS} documents are
 * dropped - they are mostly typos themselves.</li>
 * <li><b>{@value IndexFiles#SUGGESTIONS}</b>: completion phrases - the
 * leading part of page titles ("Black hole - Wikipedia" gives "Black hole") -
 * scored by the popularity of the pages carrying them (PageRank and site
 * rank). Phrases on very many pages ("Home", "Contact us") are boilerplate and
 * dropped.</li>
 * </ul>
 */
final class QueryAidsBuilder {

    static final int MIN_WORD_DOCS = 3;

    static final int MAX_PHRASE_DOCS = 500;

    static final int MAX_SUGGESTIONS = 500_000;

    private static final Pattern WORD = Pattern.compile("[a-z]{2,24}");

    // Separators between a page's own title and the site name.
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("\\s+[|\\-–—:·•»]\\s+|\\s*::\\s*");

    private final Map<String, int[]> wordDocs = new HashMap<>();

    private final Map<String, Phrase> phrases = new HashMap<>();

    private static final class Phrase {

        String display;

        List<Integer> docs = new ArrayList<>(1);

        int count;
    }

    void addDocument(int docId, String title, String text) {

        Set<String> words = new HashSet<>();

        Matcher m = WORD.matcher((title + " " + text).toLowerCase(Locale.ROOT));

        while (m.find()) {

            words.add(m.group());
        }

        for (String word : words) {

            wordDocs.computeIfAbsent(word, k -> new int[1])[0]++;
        }

        String phrase = leadingPhrase(title);

        if (phrase != null) {

            String key = normalize(phrase);

            Phrase p = phrases.computeIfAbsent(key, k -> new Phrase());

            if (p.display == null) {

                p.display = phrase;
            }

            p.count++;

            // Keep a few documents to score with; enough to rank, bounded in memory.
            if (p.docs.size() < 8) {

                p.docs.add(docId);
            }
        }
    }

    static String leadingPhrase(String title) {

        String t = title == null ? "" : title.strip();

        if (t.isEmpty()) {

            return null;
        }

        String first = TITLE_SEPARATOR.split(t, 2)[0].strip();

        if (first.length() < 2 || first.length() > 60 || first.chars().noneMatch(Character::isLetter)) {

            return null;
        }

        return first;
    }

    static String normalize(String text) {

        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    void write(Path dir, float[] pageRank, int[] domainRanks) throws IOException {

        List<String> words = new ArrayList<>();

        wordDocs.forEach((word, count) -> {

            if (count[0] >= MIN_WORD_DOCS) {

                words.add(word);
            }
        });

        words.sort(null);

        try (IndexOutput out = new IndexOutput(dir.resolve(IndexFiles.VOCABULARY))) {

            out.writeInt(words.size());

            for (String word : words) {

                out.writeString(word);

                out.writeVInt(wordDocs.get(word)[0]);
            }
        }

        record Scored(String key, String display, float score) {
        }

        List<Scored> scored = new ArrayList<>();

        phrases.forEach((key, phrase) -> {

            if (key.isEmpty() || phrase.count > MAX_PHRASE_DOCS) {

                return;
            }

            double best = 0;

            for (int doc : phrase.docs) {

                int rank = domainRanks[doc];

                double site = rank > 0 ? Math.max(0, 1 - Math.log10(rank) / 6.0) : 0;

                best = Math.max(best, pageRank[doc] * (1 + 4 * site));
            }

            // More pages titled with a phrase -> more likely to be searched for.
            scored.add(new Scored(key, phrase.display, (float) (best * Math.log(1 + phrase.count))));
        });

        scored.sort((a, b) -> Float.compare(b.score(), a.score()));

        List<Scored> kept = new ArrayList<>(scored.subList(0, Math.min(MAX_SUGGESTIONS, scored.size())));

        kept.sort((a, b) -> a.key().compareTo(b.key()));

        try (IndexOutput out = new IndexOutput(dir.resolve(IndexFiles.SUGGESTIONS))) {

            out.writeInt(kept.size());

            for (Scored s : kept) {

                out.writeString(s.key());

                out.writeString(s.display());

                out.writeInt(Float.floatToIntBits(s.score()));
            }
        }
    }
}
