package com.searchengine.crawler;

import java.io.IOException;

/**
 * Fetches and parses a web page. Abstracting the fetch behind this interface
 * lets the crawler be driven by real HTTP requests in production and by
 * in-memory fixtures in tests.
 */
public interface PageFetcher {

    org.jsoup.nodes.Document fetch(String url) throws IOException;
}
