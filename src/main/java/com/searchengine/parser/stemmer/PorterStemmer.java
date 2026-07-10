package com.searchengine.parser.stemmer;

/**
 * Porter stemming algorithm (M.F. Porter, 1980).
 * <p>
 * Reduces English words to their root form so that morphological variants
 * (e.g. "connect", "connected", "connecting", "connection") collapse to a
 * single index term. This is a faithful, self contained implementation that
 * operates on one word at a time and holds no shared mutable state between
 * calls (each {@link #stem(String)} invocation is independent).
 */
public final class PorterStemmer {

    public String stem(String word) {

        if (word == null || word.length() <= 2) {

            return word;
        }

        char[] b = word.toCharArray();

        int k = b.length - 1;

        k = step1(b, k);
        k = step2(b, k);
        k = step3(b, k);
        k = step4(b, k);
        k = step5(b, k);

        return new String(b, 0, k + 1);
    }

    /* ---- consonant / vowel helpers ---- */

    private boolean consonant(char[] b, int i) {

        char c = b[i];

        if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') {

            return false;
        }

        if (c == 'y') {

            // 'y' is a consonant unless preceded by a consonant.
            return i == 0 || !consonant(b, i - 1);
        }

        return true;
    }

    /**
     * Measures the number of consonant-vowel-consonant sequences (the "m" of
     * the algorithm) between position 0 and j inclusive.
     */
    private int measure(char[] b, int j) {

        int n = 0;

        int i = 0;

        while (true) {

            if (i > j) {

                return n;
            }

            if (!consonant(b, i)) {

                break;
            }

            i++;
        }

        i++;

        while (true) {

            while (true) {

                if (i > j) {

                    return n;
                }

                if (consonant(b, i)) {

                    break;
                }

                i++;
            }

            i++;

            n++;

            while (true) {

                if (i > j) {

                    return n;
                }

                if (!consonant(b, i)) {

                    break;
                }

                i++;
            }

            i++;
        }
    }

    private boolean vowelInStem(char[] b, int j) {

        for (int i = 0; i <= j; i++) {

            if (!consonant(b, i)) {

                return true;
            }
        }

        return false;
    }

    private boolean doubleConsonant(char[] b, int j) {

        if (j < 1) {

            return false;
        }

        if (b[j] != b[j - 1]) {

            return false;
        }

        return consonant(b, j);
    }

    /**
     * cvc: the stem ends consonant-vowel-consonant and the final consonant is
     * not w, x or y. Used to restore a final 'e'.
     */
    private boolean cvc(char[] b, int i) {

        if (i < 2 || !consonant(b, i) || consonant(b, i - 1) || !consonant(b, i - 2)) {

            return false;
        }

        char c = b[i];

        return c != 'w' && c != 'x' && c != 'y';
    }

    private boolean ends(char[] b, int k, String s) {

        int len = s.length();

        int start = k - len + 1;

        if (start < 0) {

            return false;
        }

        for (int i = 0; i < len; i++) {

            if (b[start + i] != s.charAt(i)) {

                return false;
            }
        }

        return true;
    }

    /* ---- steps ---- */

    private int step1(char[] b, int k) {

        // Step 1a: plurals.
        if (b[k] == 's') {

            if (ends(b, k, "sses")) {

                k -= 2;

            } else if (ends(b, k, "ies")) {

                k -= 2;

            } else if (k > 0 && b[k - 1] != 's') {

                k -= 1;
            }
        }

        // Step 1b: past participles / gerunds.
        boolean restore = false;

        if (ends(b, k, "eed")) {

            if (measure(b, k - 3) > 0) {

                k -= 1;
            }

        } else if (ends(b, k, "ed") && vowelInStem(b, k - 2)) {

            k -= 2;

            restore = true;

        } else if (ends(b, k, "ing") && vowelInStem(b, k - 3)) {

            k -= 3;

            restore = true;
        }

        if (restore) {

            if (ends(b, k, "at") || ends(b, k, "bl") || ends(b, k, "iz")) {

                k = append(b, k, 'e');

            } else if (doubleConsonant(b, k)) {

                char c = b[k];

                if (c != 'l' && c != 's' && c != 'z') {

                    k -= 1;
                }

            } else if (measure(b, k) == 1 && cvc(b, k)) {

                k = append(b, k, 'e');
            }
        }

        // Step 1c: terminal y -> i when there is another vowel in the stem.
        if (ends(b, k, "y") && vowelInStem(b, k - 1)) {

            b[k] = 'i';
        }

        return k;
    }

    private int append(char[] b, int k, char c) {

        // Steps only ever shorten the word, so k+1 is always within bounds of
        // the original array which held the full input word.
        b[k + 1] = c;

        return k + 1;
    }

    private int step2(char[] b, int k) {

        if (k <= 0) {

            return k;
        }

        switch (b[k - 1]) {

            case 'a' -> {
                if (ends(b, k, "ational")) return replace(b, k, "ational", "ate");
                if (ends(b, k, "tional")) return replace(b, k, "tional", "tion");
            }
            case 'c' -> {
                if (ends(b, k, "enci")) return replace(b, k, "enci", "ence");
                if (ends(b, k, "anci")) return replace(b, k, "anci", "ance");
            }
            case 'e' -> {
                if (ends(b, k, "izer")) return replace(b, k, "izer", "ize");
            }
            case 'l' -> {
                if (ends(b, k, "bli")) return replace(b, k, "bli", "ble");
                if (ends(b, k, "alli")) return replace(b, k, "alli", "al");
                if (ends(b, k, "entli")) return replace(b, k, "entli", "ent");
                if (ends(b, k, "eli")) return replace(b, k, "eli", "e");
                if (ends(b, k, "ousli")) return replace(b, k, "ousli", "ous");
            }
            case 'o' -> {
                if (ends(b, k, "ization")) return replace(b, k, "ization", "ize");
                if (ends(b, k, "ation")) return replace(b, k, "ation", "ate");
                if (ends(b, k, "ator")) return replace(b, k, "ator", "ate");
            }
            case 's' -> {
                if (ends(b, k, "alism")) return replace(b, k, "alism", "al");
                if (ends(b, k, "iveness")) return replace(b, k, "iveness", "ive");
                if (ends(b, k, "fulness")) return replace(b, k, "fulness", "ful");
                if (ends(b, k, "ousness")) return replace(b, k, "ousness", "ous");
            }
            case 't' -> {
                if (ends(b, k, "aliti")) return replace(b, k, "aliti", "al");
                if (ends(b, k, "iviti")) return replace(b, k, "iviti", "ive");
                if (ends(b, k, "biliti")) return replace(b, k, "biliti", "ble");
            }
            case 'g' -> {
                if (ends(b, k, "logi")) return replace(b, k, "logi", "log");
            }
            default -> {
            }
        }

        return k;
    }

    private int step3(char[] b, int k) {

        switch (b[k]) {

            case 'e' -> {
                if (ends(b, k, "icate")) return replace(b, k, "icate", "ic");
                if (ends(b, k, "ative")) return replace(b, k, "ative", "");
                if (ends(b, k, "alize")) return replace(b, k, "alize", "al");
            }
            case 'i' -> {
                if (ends(b, k, "iciti")) return replace(b, k, "iciti", "ic");
            }
            case 'l' -> {
                if (ends(b, k, "ical")) return replace(b, k, "ical", "ic");
                if (ends(b, k, "ful")) return replace(b, k, "ful", "");
            }
            case 's' -> {
                if (ends(b, k, "ness")) return replace(b, k, "ness", "");
            }
            default -> {
            }
        }

        return k;
    }

    private int step4(char[] b, int k) {

        if (k <= 0) {

            return k;
        }

        String[] suffixes = {
                "al", "ance", "ence", "er", "ic", "able", "ible", "ant",
                "ement", "ment", "ent", "ou", "ism", "ate", "iti", "ous",
                "ive", "ize"
        };

        for (String suffix : suffixes) {

            if (ends(b, k, suffix)) {

                int stemEnd = k - suffix.length();

                if (measure(b, stemEnd) > 1) {

                    return stemEnd;
                }

                return k;
            }
        }

        // "ion" only when preceded by s or t.
        if (ends(b, k, "ion")) {

            int stemEnd = k - 3;

            if (stemEnd >= 0 && (b[stemEnd] == 's' || b[stemEnd] == 't') && measure(b, stemEnd) > 1) {

                return stemEnd;
            }
        }

        return k;
    }

    private int step5(char[] b, int k) {

        // Step 5a: remove a final e.
        if (b[k] == 'e') {

            int m = measure(b, k - 1);

            if (m > 1 || (m == 1 && !cvc(b, k - 1))) {

                k -= 1;
            }
        }

        // Step 5b: collapse ll -> l when measure > 1.
        if (k > 0 && b[k] == 'l' && doubleConsonant(b, k) && measure(b, k) > 1) {

            k -= 1;
        }

        return k;
    }

    /**
     * Replace the {@code from} suffix (already matched at position k) with
     * {@code to}, but only when the resulting stem has a positive measure.
     */
    private int replace(char[] b, int k, String from, String to) {

        int stemEnd = k - from.length();

        if (measure(b, stemEnd) <= 0) {

            return k;
        }

        for (int i = 0; i < to.length(); i++) {

            b[stemEnd + 1 + i] = to.charAt(i);
        }

        return stemEnd + to.length();
    }
}
