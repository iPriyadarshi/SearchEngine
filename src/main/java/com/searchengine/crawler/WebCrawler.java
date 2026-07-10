package com.searchengine.crawler;

import com.searchengine.document.model.Document;
import com.searchengine.document.source.HtmlDocumentSource;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * A small breadth-first web crawler. Starting from a seed URL it fetches pages,
 * turns each into a {@link Document} (title + visible text), and follows links
 * until a page limit is reached.
 * <p>
 * Fetching is delegated to a {@link PageFetcher} so the traversal logic can be
 * exercised without network access. By default the crawl stays on the seed
 * host to avoid wandering across the whole web.
 */
public class WebCrawler {

    private final PageFetcher fetcher;

    private final int maxPages;

    private final boolean sameHostOnly;

    public WebCrawler(PageFetcher fetcher, int maxPages) {

        this(fetcher, maxPages, true);
    }

    public WebCrawler(PageFetcher fetcher, int maxPages, boolean sameHostOnly) {

        this.fetcher = fetcher;

        this.maxPages = maxPages;

        this.sameHostOnly = sameHostOnly;
    }

    public List<Document> crawl(String seedUrl) {

        List<Document> documents = new ArrayList<>();

        Set<String> visited = new HashSet<>();

        Queue<String> frontier = new ArrayDeque<>();

        String seedHost = hostOf(seedUrl);

        frontier.add(seedUrl);

        int docId = 0;

        while (!frontier.isEmpty() && documents.size() < maxPages) {

            String url = frontier.poll();

            if (!visited.add(url)) {

                continue;
            }

            org.jsoup.nodes.Document page;

            try {

                page = fetcher.fetch(url);

            } catch (IOException e) {

                // Skip pages that fail to load; keep crawling the rest.
                continue;
            }

            if (page == null) {

                continue;
            }

            documents.add(new Document(docId++, HtmlDocumentSource.textOf(page), url));

            enqueueLinks(page, seedHost, visited, frontier);
        }

        return documents;
    }

    private void enqueueLinks(org.jsoup.nodes.Document page, String seedHost,
                              Set<String> visited, Queue<String> frontier) {

        for (Element link : page.select("a[href]")) {

            String next = link.absUrl("href");

            if (next.isEmpty() || visited.contains(next)) {

                continue;
            }

            if (sameHostOnly && !seedHost.isEmpty() && !seedHost.equals(hostOf(next))) {

                continue;
            }

            frontier.add(next);
        }
    }

    private String hostOf(String url) {

        try {

            String host = URI.create(url).getHost();

            return host == null ? "" : host;

        } catch (IllegalArgumentException e) {

            return "";
        }
    }
}
