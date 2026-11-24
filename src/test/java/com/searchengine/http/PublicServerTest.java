package com.searchengine.http;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.engine.SearchEngine;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.ranking.core.TFIDFRanker;
import com.searchengine.search.MemorySearchBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour needed to run on the public internet: rate limiting, security
 * headers, compression and the static pages.
 */
public class PublicServerTest {

    private SearchHttpServer server;

    private final HttpClient client = HttpClient.newHttpClient();

    private SearchHttpServer start(SearchHttpServer.Options options) throws Exception {

        Parser parser = new DefaultParser(new RegexTokenizer(), List.of(new LowercaseFilter()));

        PositionalInvertedIndex index = new PositionalInvertedIndex();

        SearchEngine engine = new SearchEngine(parser, index);

        engine.index(List.of(new Document(0, "search engines rank documents", "d0")));

        server = new SearchHttpServer(0, new MemorySearchBackend(engine, Map.of("tfidf", new TFIDFRanker(index)),
                new PhraseQueryExecutor(index), new BooleanQueryExecutor(index, index, engine::analyzeQuery), "test"),
                options);

        server.start();

        return server;
    }

    @AfterEach
    void stop() {

        if (server != null) {

            server.stop();
        }
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + path));

        for (int i = 0; i + 1 < headers.length; i += 2) {

            request.header(headers[i], headers[i + 1]);
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void tokenBucketAllowsBurstThenRefillsPerClient() {

        RateLimiter limiter = new RateLimiter(2, 3);

        long t = 0;

        for (int i = 0; i < 3; i++) {

            assertEquals(0, limiter.acquire("a", 1, t));
        }

        assertEquals(1, limiter.acquire("a", 1, t), "burst used up: wait ~0.5s, rounded up");

        assertEquals(0, limiter.acquire("b", 1, t), "other clients are unaffected");

        assertEquals(0, limiter.acquire("a", 1, t + 500_000_000L), "half a second refills one token at 2/s");

        assertEquals(0, limiter.acquire("a", 0.25, t + 500_000_000L + 125_000_000L), "cheap requests cost less");
    }

    @Test
    void overLimitClientsGet429WithRetryAfter() throws Exception {

        start(new SearchHttpServer.Options(1, 2, false));

        assertEquals(200, get("/search?q=search").statusCode());

        assertEquals(200, get("/search?q=search").statusCode());

        HttpResponse<String> limited = get("/search?q=search");

        assertEquals(429, limited.statusCode());

        assertTrue(limited.headers().firstValue("Retry-After").isPresent());

        // Health checks and pages are not rate limited.
        assertEquals(200, get("/health").statusCode());

        assertEquals(200, get("/").statusCode());
    }

    @Test
    void trustsForwardedForOnlyWhenConfigured() throws Exception {

        start(new SearchHttpServer.Options(1, 1, true));

        assertEquals(200, get("/search?q=a", "X-Forwarded-For", "203.0.113.7").statusCode());

        assertEquals(429, get("/search?q=a", "X-Forwarded-For", "203.0.113.7").statusCode());

        // A different real client behind the same proxy has its own bucket.
        assertEquals(200, get("/search?q=a", "X-Forwarded-For", "198.51.100.4").statusCode());
    }

    @Test
    void servesPagesWithSecurityHeadersAndCompression() throws Exception {

        start(SearchHttpServer.Options.defaults());

        HttpResponse<String> home = get("/");

        assertTrue(home.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));

        assertEquals("no-referrer", home.headers().firstValue("Referrer-Policy").orElse(""));

        assertEquals("nosniff", home.headers().firstValue("X-Content-Type-Options").orElse(""));

        assertEquals("no-store", get("/search?q=search").headers().firstValue("Cache-Control").orElse(""));

        HttpResponse<String> about = get("/about");

        assertEquals(200, about.statusCode());

        assertTrue(about.body().contains("User-agent: siftbot"));

        assertTrue(get("/robots.txt").body().contains("Disallow: /search"));

        HttpResponse<byte[]> gz = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals("gzip", gz.headers().firstValue("Content-Encoding").orElse(""));

        assertTrue(gz.body().length < home.body().length() / 2, "compressed page should be much smaller");
    }
}
