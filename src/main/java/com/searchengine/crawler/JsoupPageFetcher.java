package com.searchengine.crawler;

import org.jsoup.Jsoup;

import java.io.IOException;

/**
 * {@link PageFetcher} backed by real HTTP requests via jsoup.
 */
public class JsoupPageFetcher implements PageFetcher {

    private static final String USER_AGENT =
            "SearchEngineBot/1.0 (+https://github.com/; educational crawler)";

    private final int timeoutMillis;

    public JsoupPageFetcher() {

        this(10_000);
    }

    public JsoupPageFetcher(int timeoutMillis) {

        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public org.jsoup.nodes.Document fetch(String url) throws IOException {

        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(timeoutMillis)
                .followRedirects(true)
                .get();
    }
}
