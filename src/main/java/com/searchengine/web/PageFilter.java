package com.searchengine.web;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether an extracted page belongs in a general-audience English
 * index: it must be English, have a meaningful amount of text, and not be an
 * obviously adult or spam page.
 * <p>
 * Language detection is a deliberately simple, fast heuristic: the page's
 * {@code lang} attribute when present, otherwise the share of very common
 * English function words in the text (real English prose is well above 10%;
 * other languages and keyword-stuffed spam are far below).
 */
public final class PageFilter {

    static final int MIN_TEXT_CHARS = 200;

    static final int MIN_WORDS = 40;

    private static final double MIN_ENGLISH_RATIO = 0.10;

    private static final int SAMPLE_WORDS = 400;

    private static final Pattern WORD = Pattern.compile("[a-z]+");

    private static final Set<String> ENGLISH_FUNCTION_WORDS = Set.of(
            "the", "of", "and", "to", "in", "a", "is", "that", "for", "it", "as", "with", "was", "on", "be",
            "by", "are", "this", "you", "or", "from", "at", "have", "an", "not", "your", "we", "can", "will",
            "has", "but", "which", "their", "they", "all", "more", "its", "his", "her", "if", "about", "our",
            "one", "were", "there", "been", "also", "when", "who", "would", "what", "into", "how", "than");

    // Conservative: only unambiguous terms, matched against URL and title, never
    // body text (so e.g. "Ford Escort" or an article on sex education survive).
    private static final Pattern ADULT = Pattern.compile(
            "porn|xxx|xvideo|xhamster|xnxx|redtube|youporn|hentai|nsfw|camgirl|sexcam|onlyfans",
            Pattern.CASE_INSENSITIVE);

    private PageFilter() {
    }

    public enum Verdict {
        ACCEPT, NOT_ENGLISH, TOO_SHORT, ADULT
    }

    public static Verdict judge(HtmlExtractor.Page page) {

        if (ADULT.matcher(page.url()).find() || ADULT.matcher(page.title()).find()) {

            return Verdict.ADULT;
        }

        if (page.text().length() < MIN_TEXT_CHARS) {

            return Verdict.TOO_SHORT;
        }

        String lang = page.language().toLowerCase(Locale.ROOT);

        if (!lang.isEmpty() && !lang.startsWith("en")) {

            return Verdict.NOT_ENGLISH;
        }

        return looksEnglish(page.text()) ? Verdict.ACCEPT : Verdict.NOT_ENGLISH;
    }

    static boolean looksEnglish(String text) {

        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));

        int words = 0;

        int common = 0;

        while (matcher.find() && words < SAMPLE_WORDS) {

            words++;

            if (ENGLISH_FUNCTION_WORDS.contains(matcher.group())) {

                common++;
            }
        }

        return words >= MIN_WORDS && (double) common / words >= MIN_ENGLISH_RATIO;
    }
}
