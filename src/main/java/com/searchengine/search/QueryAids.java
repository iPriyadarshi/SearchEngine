package com.searchengine.search;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spelling correction and autocomplete, from the vocabulary and suggestion
 * files written with the index (absent files simply disable the feature).
 * <p>
 * <b>Spelling</b> follows Norvig's approach: for a query word that is unknown
 * or rare, candidate words one edit away (delete, transpose, replace, insert)
 * - and two edits away for longer words - are looked up, and the one used by
 * the most documents wins, if it is clearly more common than the original.
 * <p>
 * <b>Autocomplete</b> binary-searches the sorted phrases for the typed
 * prefix and returns the highest-scored matches; for 1-3 character prefixes,
 * where matches are many, the answers are precomputed at load time.
 */
public final class QueryAids {

    static final int MAX_COMPLETIONS = 8;

    private static final int SCAN_LIMIT = 20_000;

    private static final int PRECOMPUTED_PREFIX = 3;

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

    private final Map<String, Integer> vocabulary;

    private final String[] keys;

    private final String[] displays;

    private final float[] scores;

    private final Map<String, int[]> topForShortPrefix = new HashMap<>();

    private QueryAids(Map<String, Integer> vocabulary, String[] keys, String[] displays, float[] scores) {

        this.vocabulary = vocabulary;

        this.keys = keys;

        this.displays = displays;

        this.scores = scores;

        precomputeShortPrefixes();
    }

    public static QueryAids load(Path indexDir) throws IOException {

        Map<String, Integer> vocabulary = new HashMap<>();

        Path vocab = indexDir.resolve("vocab.bin");

        if (Files.exists(vocab)) {

            try (DataInputStream in = open(vocab)) {

                int n = in.readInt();

                for (int i = 0; i < n; i++) {

                    vocabulary.put(readString(in), readVInt(in));
                }
            }
        }

        String[] keys = new String[0];

        String[] displays = new String[0];

        float[] scores = new float[0];

        Path suggest = indexDir.resolve("suggest.bin");

        if (Files.exists(suggest)) {

            try (DataInputStream in = open(suggest)) {

                int n = in.readInt();

                keys = new String[n];

                displays = new String[n];

                scores = new float[n];

                for (int i = 0; i < n; i++) {

                    keys[i] = readString(in);

                    displays[i] = readString(in);

                    scores[i] = Float.intBitsToFloat(in.readInt());
                }
            }
        }

        return new QueryAids(vocabulary, keys, displays, scores);
    }

    static QueryAids of(Map<String, Integer> vocabulary, List<String[]> phrases) {

        List<String[]> sorted = new ArrayList<>(phrases);

        sorted.sort((a, b) -> a[0].compareTo(b[0]));

        String[] keys = new String[sorted.size()];

        String[] displays = new String[sorted.size()];

        float[] scores = new float[sorted.size()];

        for (int i = 0; i < sorted.size(); i++) {

            keys[i] = sorted.get(i)[0];

            displays[i] = sorted.get(i)[1];

            scores[i] = Float.parseFloat(sorted.get(i)[2]);
        }

        return new QueryAids(vocabulary, keys, displays, scores);
    }

    // ---------- spelling ----------

    /**
     * The query with likely misspellings corrected, or {@code null} if no
     * word needs changing. Operators (AND/OR/NOT) and quotes are kept.
     */
    public String correct(String query) {

        if (vocabulary.isEmpty()) {

            return null;
        }

        StringBuilder out = new StringBuilder();

        boolean changed = false;

        // Split into words and the text between them, so punctuation survives.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z]+").matcher(query);

        int last = 0;

        while (m.find()) {

            out.append(query, last, m.start());

            String word = m.group();

            String fixed = word.equals("AND") || word.equals("OR") || word.equals("NOT") ? null : correctWord(word);

            if (fixed != null) {

                out.append(fixed);

                changed = true;

            } else {

                out.append(word);
            }

            last = m.end();
        }

        out.append(query.substring(last));

        return changed ? out.toString() : null;
    }

    String correctWord(String original) {

        String word = original.toLowerCase(Locale.ROOT);

        if (word.length() < 3) {

            return null;
        }

        int known = vocabulary.getOrDefault(word, 0);

        String best = null;

        int bestCount = 0;

        Set<String> first = edits(word);

        for (String candidate : first) {

            int count = vocabulary.getOrDefault(candidate, 0);

            if (count > bestCount) {

                best = candidate;

                bestCount = count;
            }
        }

        if (best == null && word.length() >= 5) {

            for (String e1 : first) {

                for (String candidate : edits(e1)) {

                    int count = vocabulary.getOrDefault(candidate, 0);

                    if (count > bestCount) {

                        best = candidate;

                        bestCount = count;
                    }
                }
            }
        }

        // Only replace a word the index knows if the alternative is far more common:
        // "teh" vs "the" is thousands of times rarer; a plural or variant spelling is not.
        if (best == null || (known > 0 && bestCount < 100 * known)) {

            return null;
        }

        return Character.isUpperCase(original.charAt(0)) ? Character.toUpperCase(best.charAt(0)) + best.substring(1) : best;
    }

    static Set<String> edits(String word) {

        Set<String> result = new LinkedHashSet<>();

        for (int i = 0; i <= word.length(); i++) {

            String left = word.substring(0, i);

            String right = word.substring(i);

            if (!right.isEmpty()) {

                result.add(left + right.substring(1));
            }

            if (right.length() > 1) {

                result.add(left + right.charAt(1) + right.charAt(0) + right.substring(2));
            }

            for (int c = 0; c < ALPHABET.length(); c++) {

                char ch = ALPHABET.charAt(c);

                if (!right.isEmpty()) {

                    result.add(left + ch + right.substring(1));
                }

                result.add(left + ch + right);
            }
        }

        result.remove(word);

        return result;
    }

    // ---------- autocomplete ----------

    /**
     * Up to {@value #MAX_COMPLETIONS} completions of a typed prefix, most
     * popular first.
     */
    public List<String> complete(String prefix) {

        String p = prefix.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").stripLeading();

        if (p.isBlank() || keys.length == 0) {

            return List.of();
        }

        if (p.length() <= PRECOMPUTED_PREFIX && !p.endsWith(" ")) {

            int[] top = topForShortPrefix.get(p);

            List<String> result = new ArrayList<>();

            if (top != null) {

                for (int i : top) {

                    result.add(displays[i]);
                }
            }

            return result;
        }

        int from = lowerBound(p);

        List<Integer> matches = new ArrayList<>();

        for (int i = from; i < keys.length && i - from < SCAN_LIMIT && keys[i].startsWith(p); i++) {

            matches.add(i);
        }

        matches.sort((a, b) -> Float.compare(scores[b], scores[a]));

        List<String> result = new ArrayList<>();

        for (int i = 0; i < Math.min(MAX_COMPLETIONS, matches.size()); i++) {

            result.add(displays[matches.get(i)]);
        }

        return result;
    }

    private int lowerBound(String prefix) {

        int lo = 0;

        int hi = keys.length;

        while (lo < hi) {

            int mid = (lo + hi) >>> 1;

            if (keys[mid].compareTo(prefix) < 0) {

                lo = mid + 1;

            } else {

                hi = mid;
            }
        }

        return lo;
    }

    private void precomputeShortPrefixes() {

        Map<String, int[]> top = new HashMap<>();

        for (int i = 0; i < keys.length; i++) {

            for (int len = 1; len <= Math.min(PRECOMPUTED_PREFIX, keys[i].length()); len++) {

                String prefix = keys[i].substring(0, len);

                if (prefix.endsWith(" ")) {

                    break;
                }

                int[] slots = top.computeIfAbsent(prefix, k -> new int[0]);

                top.put(prefix, insert(slots, i));
            }
        }

        topForShortPrefix.putAll(top);
    }

    /**
     * Insert entry i into a best-first array capped at MAX_COMPLETIONS.
     */
    private int[] insert(int[] slots, int i) {

        int pos = slots.length;

        while (pos > 0 && scores[slots[pos - 1]] < scores[i]) {

            pos--;
        }

        if (pos >= MAX_COMPLETIONS) {

            return slots;
        }

        int[] next = Arrays.copyOf(slots, Math.min(MAX_COMPLETIONS, slots.length + 1));

        System.arraycopy(slots, pos, next, pos + 1, next.length - pos - 1);

        next[pos] = i;

        return next;
    }

    private static DataInputStream open(Path file) throws IOException {

        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
    }

    private static int readVInt(DataInputStream in) throws IOException {

        int b = in.readUnsignedByte();

        int value = b & 0x7F;

        for (int shift = 7; (b & 0x80) != 0; shift += 7) {

            b = in.readUnsignedByte();

            value |= (b & 0x7F) << shift;
        }

        return value;
    }

    private static String readString(DataInputStream in) throws IOException {

        byte[] bytes = new byte[readVInt(in)];

        in.readFully(bytes);

        return new String(bytes, StandardCharsets.UTF_8);
    }
}
