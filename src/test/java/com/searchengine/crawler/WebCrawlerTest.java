package com.searchengine.crawler;

import com.searchengine.document.model.Document;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebCrawlerTest {

    /**
     * In-memory site used instead of real HTTP. Relative hrefs resolve against
     * the page URL (the jsoup base uri).
     */
    private PageFetcher fakeSite() {

        Map<String, String> pages = Map.of(
                "http://example.com/index.html",
                "<title>Home</title><a href='page-a.html'>A</a><a href='page-b.html'>B</a>"
                        + "<a href='http://other.com/x.html'>external</a>",
                "http://example.com/page-a.html",
                "<title>Alpha</title><p>alpha content</p><a href='page-b.html'>B again</a>",
                "http://example.com/page-b.html",
                "<title>Beta</title><p>beta content</p>",
                "http://other.com/x.html",
                "<title>External</title><p>should not be crawled</p>");

        return url -> {

            String html = pages.get(url);

            return html == null ? null : Jsoup.parse(html, url);
        };
    }

    @Test
    void shouldCrawlSameHostBreadthFirst() {

        WebCrawler crawler = new WebCrawler(fakeSite(), 10);

        List<Document> docs = crawler.crawl("http://example.com/index.html");

        List<String> urls = docs.stream().map(Document::getPath).toList();

        assertEquals(3, docs.size());

        assertTrue(urls.contains("http://example.com/index.html"));

        assertTrue(urls.contains("http://example.com/page-a.html"));

        assertTrue(urls.contains("http://example.com/page-b.html"));

        // external host is not followed by default
        assertTrue(!urls.contains("http://other.com/x.html"));
    }

    @Test
    void shouldRespectPageLimit() {

        WebCrawler crawler = new WebCrawler(fakeSite(), 2);

        List<Document> docs = crawler.crawl("http://example.com/index.html");

        assertEquals(2, docs.size());
    }

    @Test
    void shouldExtractTitleAndText() {

        WebCrawler crawler = new WebCrawler(fakeSite(), 10);

        List<Document> docs = crawler.crawl("http://example.com/page-a.html");

        // page-a links only to page-b (same host), so 2 pages are reached
        Document alpha = docs.stream()
                .filter(d -> d.getPath().endsWith("page-a.html"))
                .findFirst()
                .orElseThrow();

        assertTrue(alpha.getContent().contains("Alpha"));

        assertTrue(alpha.getContent().contains("alpha content"));
    }
}
