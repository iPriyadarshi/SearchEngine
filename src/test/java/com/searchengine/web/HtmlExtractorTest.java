package com.searchengine.web;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HtmlExtractorTest {

    private static final String PAGE = """
            <html lang="en-GB"><head>
              <title>  Rust Programming   Language </title>
              <meta name="description" content="A language empowering everyone to build reliable software.">
              <link rel="canonical" href="https://www.rust-lang.org/?utm_source=x">
            </head><body>
              <nav><a href="/learn">Learn</a> <a href="/tools">Tools</a></nav>
              <header>Site header text</header>
              <main>
                <h1>Rust</h1>
                <p>Rust is blazingly fast and memory-efficient: with no runtime or garbage collector,
                it can power performance-critical services, run on embedded devices, and easily
                integrate with other languages. Read <a href="https://doc.rust-lang.org/book/">the book</a>
                or see <a href="https://crates.io/" rel="nofollow">crates</a>.</p>
                <script>var tracking = 1;</script>
              </main>
              <footer>Copyright footer</footer>
            </body></html>
            """;

    @Test
    void extractsFieldsAndDropsChrome() {

        HtmlExtractor.Page page = HtmlExtractor.extract(Jsoup.parse(PAGE, "https://www.rust-lang.org/en-US"),
                "https://www.rust-lang.org/en-US");

        assertEquals("https://www.rust-lang.org/", page.url());

        assertEquals("Rust Programming Language", page.title());

        assertEquals("A language empowering everyone to build reliable software.", page.description());

        assertEquals("en-GB", page.language());

        assertTrue(page.text().contains("blazingly fast"), page.text());

        for (String chrome : new String[]{"Site header", "Copyright", "tracking", "Tools"}) {

            assertFalse(page.text().contains(chrome), chrome + " in " + page.text());
        }

        // nav links are still links (they matter for the graph); nofollow is dropped.
        assertTrue(page.links().stream().anyMatch(l -> l.url().equals("https://doc.rust-lang.org/book/")
                && l.anchor().equals("the book")));

        assertTrue(page.links().stream().anyMatch(l -> l.url().equals("https://www.rust-lang.org/learn")));

        assertFalse(page.links().stream().anyMatch(l -> l.url().contains("crates.io")));
    }

    @Test
    void honoursNoindexAndFallsBackForTitle() {

        assertNull(HtmlExtractor.extract(Jsoup.parse(
                "<html><head><meta name=robots content='noindex,follow'></head><body>x</body></html>"),
                "https://example.org/"));

        HtmlExtractor.Page page = HtmlExtractor.extract(Jsoup.parse(
                "<html><body><h1>Only a heading</h1><p>text</p></body></html>"), "https://example.org/a");

        assertEquals("Only a heading", page.title());
    }

    @Test
    void crossHostCanonicalIsIgnored() {

        HtmlExtractor.Page page = HtmlExtractor.extract(Jsoup.parse(
                "<html><head><link rel=canonical href='https://spam.example/'></head><body>x</body></html>"),
                "https://good.example/page");

        assertEquals("https://good.example/page", page.url());
    }
}
