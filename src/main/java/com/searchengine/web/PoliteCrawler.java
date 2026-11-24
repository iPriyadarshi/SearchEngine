package com.searchengine.web;

import com.searchengine.document.model.Document;
import com.searchengine.document.model.Link;
import org.jsoup.Jsoup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Crawls one site at a time, the way a well-behaved crawler must:
 * <ul>
 * <li>reads and obeys {@code robots.txt} for our user agent (a server error
 * on robots.txt means "stay away");</li>
 * <li>sends one request at a time to the site, at least
 * {@link Options#minDelayMillis} apart, longer if the site asks via
 * {@code Crawl-delay} (capped);</li>
 * <li>stays on the site's host, takes a bounded number of pages, and visits
 * them breadth-first from the home page, so the most important pages come
 * first;</li>
 * <li>skips URLs that are obviously not web pages (images, archives, ...).</li>
 * </ul>
 * Many sites are crawled in parallel by running several {@code crawlSite}
 * calls on different threads; politeness is per site.
 */
public final class PoliteCrawler {

    public static final String AGENT_TOKEN = "siftbot";

    private static final Pattern NOT_A_PAGE = Pattern.compile(
            "\\.(jpe?g|png|gif|webp|svg|ico|bmp|tiff?|pdf|zip|gz|tgz|rar|7z|exe|dmg|msi|apk|mp[34]|m4a|wav|avi|mov|"
                    + "mkv|webm|css|js|json|xml|rss|atom|woff2?|ttf|eot|txt|csv|docx?|xlsx?|pptx?)(\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Fetches raw bytes; the HTTP implementation and test fakes implement it.
     */
    public interface Fetcher {

        /**
         * @return the response, or empty if the URL could not be fetched
         */
        Optional<Response> get(String url, int maxBytes) throws IOException, InterruptedException;
    }

    /**
     * @param finalUrl the URL after redirects
     */
    public record Response(int status, String finalUrl, String contentType, byte[] body) {
    }

    public record Options(int maxPagesPerSite, long minDelayMillis, long maxDelayMillis, int maxPageBytes) {

        public static Options defaults() {

            return new Options(25, 1_000, 10_000, 2 << 20);
        }
    }

    @FunctionalInterface
    public interface PageSink {

        void accept(Document page) throws IOException;
    }

    private final Fetcher fetcher;

    private final Options options;

    public PoliteCrawler(Fetcher fetcher, Options options) {

        this.fetcher = fetcher;

        this.options = options;
    }

    /**
     * Crawl one site starting from its home page.
     *
     * @return the number of pages handed to {@code sink}
     */
    public int crawlSite(String homeUrl, PageSink sink) throws InterruptedException {

        String start = UrlNormalizer.normalize(homeUrl);

        if (start == null) {

            return 0;
        }

        RobotsTxt robots = robots(start);

        long delay = Math.min(options.maxDelayMillis(),
                Math.max(options.minDelayMillis(), (long) (robots.crawlDelaySeconds() * 1000)));

        Queue<String> frontier = new ArrayDeque<>();

        Set<String> seen = new HashSet<>();

        frontier.add(start);

        seen.add(start);

        String siteHost = null;

        int pages = 0;

        int attempts = 0;

        // Bound requests, not just pages, so a site of errors cannot hold a thread forever.
        while (!frontier.isEmpty() && pages < options.maxPagesPerSite() && attempts < options.maxPagesPerSite() * 3) {

            String url = frontier.poll();

            if (!robots.isAllowed(pathAndQuery(url))) {

                continue;
            }

            if (attempts++ > 0) {

                Thread.sleep(delay);
            }

            Optional<Response> fetched;

            try {

                fetched = fetcher.get(url, options.maxPageBytes());

            } catch (IOException e) {

                continue;
            }

            if (fetched.isEmpty() || fetched.get().status() != 200
                    || !fetched.get().contentType().toLowerCase(Locale.ROOT).contains("html")) {

                continue;
            }

            Response response = fetched.get();

            String finalUrl = UrlNormalizer.normalize(response.finalUrl());

            if (finalUrl == null) {

                continue;
            }

            // The home page's final host (after e.g. a www redirect) defines the site.
            if (siteHost == null) {

                siteHost = UrlNormalizer.host(finalUrl);
            }

            if (!UrlNormalizer.host(finalUrl).equals(siteHost)) {

                continue;
            }

            HtmlExtractor.Page page;

            try {

                page = HtmlExtractor.extract(Jsoup.parse(new ByteArrayInputStream(response.body()),
                        charset(response.contentType()), finalUrl), finalUrl);

            } catch (IOException | RuntimeException e) {

                continue;
            }

            if (page == null) {

                continue;
            }

            for (Link link : page.links()) {

                if (UrlNormalizer.host(link.url()).equals(siteHost) && !NOT_A_PAGE.matcher(link.url()).find()
                        && seen.add(link.url())) {

                    frontier.add(link.url());
                }
            }

            // Home pages are often short; keep them anyway, since they are what
            // navigational queries look for. Other pages must pass the filter.
            boolean home = pages == 0;

            PageFilter.Verdict verdict = PageFilter.judge(page);

            boolean keep = verdict == PageFilter.Verdict.ACCEPT
                    || (home && verdict == PageFilter.Verdict.TOO_SHORT && !page.title().isEmpty());

            if (keep) {

                try {

                    sink.accept(page.toDocument());

                } catch (IOException e) {

                    throw new IllegalStateException("Cannot store page " + finalUrl, e);
                }

                pages++;

            } else if (home && verdict != PageFilter.Verdict.TOO_SHORT) {

                // A non-English or adult home page: the rest of the site will be too.
                break;
            }
        }

        return pages;
    }

    private RobotsTxt robots(String homeUrl) throws InterruptedException {

        URI home = URI.create(homeUrl);

        String robotsUrl = home.getScheme() + "://" + home.getRawAuthority() + "/robots.txt";

        try {

            Optional<Response> response = fetcher.get(robotsUrl, 512 * 1024);

            if (response.isEmpty()) {

                return RobotsTxt.allowAll();
            }

            int status = response.get().status();

            if (status >= 500) {

                return RobotsTxt.disallowAll();
            }

            if (status != 200) {

                return RobotsTxt.allowAll();
            }

            return RobotsTxt.parse(new String(response.get().body(), java.nio.charset.StandardCharsets.UTF_8), AGENT_TOKEN);

        } catch (IOException e) {

            return RobotsTxt.allowAll();
        }
    }

    private static String pathAndQuery(String url) {

        URI uri = URI.create(url);

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String charset(String contentType) {

        int at = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");

        if (at < 0) {

            return null;
        }

        String value = contentType.substring(at + 8).replaceAll("[\"';].*", "").strip();

        return value.isEmpty() ? null : value;
    }

    /**
     * Reads at most {@code max} bytes; returns {@code null} if the body is larger.
     */
    static byte[] readLimited(InputStream in, int max) throws IOException {

        byte[] data = in.readNBytes(max + 1);

        return data.length > max ? null : data;
    }
}
