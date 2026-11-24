package com.searchengine.web;

import com.searchengine.document.model.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PoliteCrawlerTest {

    // ---------- robots.txt ----------

    @Test
    void robotsPrefersOurGroupAndLongestMatch() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /

                User-agent: SiftBot
                User-agent: otherbot
                Disallow: /private
                Allow: /private/public
                Disallow: /*.json$
                Crawl-delay: 2.5
                """, "siftbot");

        assertTrue(robots.isAllowed("/"));

        assertFalse(robots.isAllowed("/private/x"));

        assertTrue(robots.isAllowed("/private/public/page"));

        assertFalse(robots.isAllowed("/data/feed.json"));

        assertTrue(robots.isAllowed("/data/feed.json?x=1"));

        assertEquals(2.5, robots.crawlDelaySeconds());
    }

    @Test
    void robotsFallsBackToWildcardGroup() {

        RobotsTxt robots = RobotsTxt.parse("""
                # comment
                User-agent: googlebot
                Disallow:

                User-agent: *
                Disallow: /search
                Disallow: /tmp/ # trailing comment
                """, "siftbot");

        assertFalse(robots.isAllowed("/search?q=1"));

        assertFalse(robots.isAllowed("/tmp/a"));

        assertTrue(robots.isAllowed("/about"));

        assertTrue(RobotsTxt.disallowAll().isAllowed("/robots.txt"));

        assertFalse(RobotsTxt.disallowAll().isAllowed("/"));
    }

    // ---------- crawler ----------

    private static final String PROSE = "This page explains the topic in plain English with enough words to be indexed. ".repeat(4);

    private static String page(String title, String... links) {

        StringBuilder html = new StringBuilder("<html lang=en><head><title>" + title + "</title></head><body><p>" + PROSE);

        for (String link : links) {

            html.append("<a href='").append(link).append("'>").append(link).append("</a> ");
        }

        return html.append("</p></body></html>").toString();
    }

    /**
     * In-memory site; records every requested URL.
     */
    private static final class FakeSite implements PoliteCrawler.Fetcher {

        final Map<String, PoliteCrawler.Response> pages = new HashMap<>();

        final List<String> requested = new ArrayList<>();

        void html(String url, String body) {

            pages.put(url, new PoliteCrawler.Response(200, url, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Optional<PoliteCrawler.Response> get(String url, int maxBytes) {

            requested.add(url);

            PoliteCrawler.Response response = pages.get(url);

            return Optional.of(response != null ? response : new PoliteCrawler.Response(404, url, "text/html", new byte[0]));
        }
    }

    private static PoliteCrawler crawler(FakeSite site, int maxPages) {

        return new PoliteCrawler(site, new PoliteCrawler.Options(maxPages, 0, 0, 1 << 20));
    }

    @Test
    void crawlsBreadthFirstWithinTheSiteAndObeysRobots() throws Exception {

        FakeSite site = new FakeSite();

        site.pages.put("https://example.org/robots.txt", new PoliteCrawler.Response(200,
                "https://example.org/robots.txt", "text/plain", "User-agent: *\nDisallow: /admin\n".getBytes()));

        site.html("https://example.org/", page("Example home", "/a", "/admin/panel", "/logo.png", "https://other.example/x"));

        site.html("https://example.org/a", page("Page A", "/b"));

        site.html("https://example.org/b", page("Page B"));

        List<Document> crawled = new ArrayList<>();

        int count = crawler(site, 10).crawlSite("https://example.org/", crawled::add);

        assertEquals(3, count);

        assertEquals(List.of("https://example.org/", "https://example.org/a", "https://example.org/b"),
                crawled.stream().map(Document::getPath).toList());

        assertFalse(site.requested.contains("https://example.org/admin/panel"), "robots.txt disallows /admin");

        assertFalse(site.requested.contains("https://example.org/logo.png"), "not a page");

        assertFalse(site.requested.contains("https://other.example/x"), "stays on the site");
    }

    @Test
    void stopsAtThePageLimitAndRespectsServerErrorsOnRobots() throws Exception {

        FakeSite site = new FakeSite();

        site.html("https://big.example/", page("Big", "/1", "/2", "/3", "/4"));

        for (int i = 1; i <= 4; i++) {

            site.html("https://big.example/" + i, page("Page " + i));
        }

        assertEquals(2, crawler(site, 2).crawlSite("https://big.example/", doc -> { }));

        FakeSite down = new FakeSite();

        down.pages.put("https://down.example/robots.txt", new PoliteCrawler.Response(503,
                "https://down.example/robots.txt", "text/plain", new byte[0]));

        down.html("https://down.example/", page("Down"));

        assertEquals(0, crawler(down, 5).crawlSite("https://down.example/", doc -> { }));

        assertEquals(List.of("https://down.example/robots.txt"), down.requested);
    }
}
