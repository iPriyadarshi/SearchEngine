package com.searchengine.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link PoliteCrawler.Fetcher} over the JDK HTTP client, identifying itself
 * with {@link CommonCrawlSource#USER_AGENT}. Bodies larger than the limit are
 * dropped rather than truncated, so a page is never indexed half-read.
 */
public final class HttpFetcher implements PoliteCrawler.Fetcher {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Optional<PoliteCrawler.Response> get(String url, int maxBytes) throws IOException, InterruptedException {

        HttpRequest request;

        try {

            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", CommonCrawlSource.USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.5,*/*;q=0.1")
                    .header("Accept-Language", "en")
                    .GET()
                    .build();

        } catch (IllegalArgumentException e) {

            return Optional.empty();
        }

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream body = response.body()) {

            String contentType = response.headers().firstValue("Content-Type").orElse("");

            byte[] bytes = response.statusCode() == 200 ? PoliteCrawler.readLimited(body, maxBytes) : new byte[0];

            if (bytes == null) {

                return Optional.empty();
            }

            return Optional.of(new PoliteCrawler.Response(response.statusCode(), response.uri().toString(), contentType, bytes));
        }
    }
}
