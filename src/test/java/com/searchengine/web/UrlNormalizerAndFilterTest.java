package com.searchengine.web;

import com.searchengine.document.model.Link;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UrlNormalizerAndFilterTest {

    @Test
    void normalizesEquivalentUrls() {

        assertEquals("https://example.com/", UrlNormalizer.normalize("HTTPS://Example.COM:443"));

        assertEquals("http://example.com:8080/a?b=1",
                UrlNormalizer.normalize("http://example.com:8080/a?utm_source=x&b=1&fbclid=y#frag"));

        assertEquals("https://example.com/a%20b", UrlNormalizer.normalize("https://example.com/a b"));

        assertNull(UrlNormalizer.normalize("mailto:someone@example.com"));

        assertNull(UrlNormalizer.normalize("javascript:void(0)"));

        assertNull(UrlNormalizer.normalize("not a url"));

        assertEquals("example.com", UrlNormalizer.host("https://www.example.com/x"));
    }

    private static HtmlExtractor.Page page(String url, String title, String lang, String text) {

        return new HtmlExtractor.Page(url, title, "", text, lang, List.<Link>of());
    }

    @Test
    void acceptsEnglishProseAndRejectsTheRest() {

        String english = ("The history of the printing press is one of the most important stories in the world. "
                + "It changed how people shared ideas, and it was used to spread news across Europe. ").repeat(3);

        String german = ("Die Geschichte der Druckerpresse ist eine der wichtigsten Geschichten der Welt. "
                + "Sie veränderte, wie Menschen Ideen teilten, und verbreitete Nachrichten in Europa. ").repeat(3);

        assertEquals(PageFilter.Verdict.ACCEPT, PageFilter.judge(page("https://a.org/", "Printing", "", english)));

        assertEquals(PageFilter.Verdict.ACCEPT, PageFilter.judge(page("https://a.org/", "Printing", "en-US", english)));

        assertEquals(PageFilter.Verdict.NOT_ENGLISH, PageFilter.judge(page("https://a.de/", "Druck", "", german)));

        assertEquals(PageFilter.Verdict.NOT_ENGLISH, PageFilter.judge(page("https://a.de/", "Druck", "de", english)));

        assertEquals(PageFilter.Verdict.TOO_SHORT, PageFilter.judge(page("https://a.org/", "Hi", "en", "Hello there.")));

        assertEquals(PageFilter.Verdict.ADULT, PageFilter.judge(page("https://xxx-site.example/", "Videos", "en", english)));

        // Ordinary words that merely resemble blocked terms are fine.
        assertEquals(PageFilter.Verdict.ACCEPT, PageFilter.judge(page("https://cars.example/ford-escort", "Ford Escort", "en", english)));
    }
}
