package com.searchengine.semantic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT's WordPiece tokenizer (uncased), as used by bge-small-en-v1.5.
 * <ol>
 * <li><b>Basic tokenization:</b> drop control characters, lower-case, strip
 * accents, isolate CJK characters and punctuation, split on whitespace.</li>
 * <li><b>WordPiece:</b> split each word into the longest vocabulary pieces,
 * left to right, continuation pieces prefixed "##"; a word that cannot be
 * covered becomes [UNK].</li>
 * </ol>
 * Output is wrapped as {@code [CLS] ... [SEP]} and truncated to a token limit.
 */
public final class WordPieceTokenizer {

    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab;

    private final int cls;

    private final int sep;

    private final int unk;

    WordPieceTokenizer(Map<String, Integer> vocab) {

        this.vocab = vocab;

        this.cls = id(vocab, "[CLS]");

        this.sep = id(vocab, "[SEP]");

        this.unk = id(vocab, "[UNK]");
    }

    private static int id(Map<String, Integer> vocab, String token) {

        Integer id = vocab.get(token);

        if (id == null) {

            throw new IllegalArgumentException("Vocabulary has no " + token);
        }

        return id;
    }

    /**
     * vocab.txt: one token per line, the line number (from 0) is its id.
     */
    public static WordPieceTokenizer load(Path vocabFile) throws IOException {

        Map<String, Integer> vocab = new HashMap<>();

        List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);

        for (int i = 0; i < lines.size(); i++) {

            vocab.putIfAbsent(lines.get(i), i);
        }

        return new WordPieceTokenizer(vocab);
    }

    /**
     * Token ids of {@code [CLS] text [SEP]}, at most {@code maxTokens} long.
     */
    public int[] encode(String text, int maxTokens) {

        List<Integer> ids = new ArrayList<>();

        ids.add(cls);

        outer:
        for (String word : basicTokens(text)) {

            for (int piece : wordPieces(word)) {

                if (ids.size() >= maxTokens - 1) {

                    break outer;
                }

                ids.add(piece);
            }
        }

        ids.add(sep);

        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    static List<String> basicTokens(String text) {

        String lowered = stripAccents(text.toLowerCase());

        List<String> tokens = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        for (int i = 0; i < lowered.length(); ) {

            int cp = lowered.codePointAt(i);

            i += Character.charCount(cp);

            if (cp == 0 || cp == 0xFFFD || (Character.isISOControl(cp) && !Character.isWhitespace(cp))) {

                continue;
            }

            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {

                flush(current, tokens);

            } else if (isPunctuation(cp) || isCjk(cp)) {

                flush(current, tokens);

                tokens.add(new String(Character.toChars(cp)));

            } else {

                current.appendCodePoint(cp);
            }
        }

        flush(current, tokens);

        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {

        if (!current.isEmpty()) {

            tokens.add(current.toString());

            current.setLength(0);
        }
    }

    private static String stripAccents(String text) {

        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);

        StringBuilder sb = new StringBuilder(decomposed.length());

        for (int i = 0; i < decomposed.length(); i++) {

            char c = decomposed.charAt(i);

            if (Character.getType(c) != Character.NON_SPACING_MARK) {

                sb.append(c);
            }
        }

        return sb.toString();
    }

    /**
     * BERT treats all non-alphanumeric ASCII as punctuation, plus Unicode P*.
     */
    private static boolean isPunctuation(int cp) {

        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {

            return true;
        }

        int type = Character.getType(cp);

        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int cp) {

        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    List<Integer> wordPieces(String word) {

        if (word.length() > MAX_WORD_CHARS) {

            return List.of(unk);
        }

        List<Integer> pieces = new ArrayList<>();

        int start = 0;

        while (start < word.length()) {

            int end = word.length();

            Integer found = null;

            while (start < end) {

                String piece = (start > 0 ? "##" : "") + word.substring(start, end);

                found = vocab.get(piece);

                if (found != null) {

                    break;
                }

                end--;
            }

            if (found == null) {

                return List.of(unk);
            }

            pieces.add(found);

            start = end;
        }

        return pieces;
    }
}
