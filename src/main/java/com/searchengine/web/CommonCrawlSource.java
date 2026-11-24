package com.searchengine.web;

import com.searchengine.document.model.Document;
import org.jsoup.Jsoup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Reads web pages from Common Crawl WARC files: local {@code .warc(.gz)}
 * files, or files streamed straight from {@code data.commoncrawl.org} so
 * nothing is stored on disk.
 * <p>
 * {@link #records()} yields raw HTML records in file order; {@link #toDocument}
 * (thread-safe, meant for worker threads) parses, extracts and filters one
 * record, returning {@code null} for pages that do not belong in the index.
 * A file that fails mid-stream is reported and the next file is read.
 */
public final class CommonCrawlSource {

    public static final String BASE_URL = "https://data.commoncrawl.org/";

    public static final String USER_AGENT = "siftbot/1.0 (+https://github.com/iPriyadarshi/SearchEngine)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Accepted, not English, too short, adult, unparseable.
    private static final AtomicLongArray STATS = new AtomicLongArray(5);

    private final List<String> locations;

    private final Consumer<String> log;

    /**
     * @param locations local paths or http(s) URLs of WARC files
     */
    public CommonCrawlSource(List<String> locations, Consumer<String> log) {

        this.locations = List.copyOf(locations);

        this.log = log;
    }

    /**
     * WARC file URLs for {@code count} files of a crawl, starting at
     * {@code start} in its {@code warc.paths.gz} listing.
     */
    public static List<String> crawlFiles(String crawlId, int start, int count) throws IOException {

        byte[] gz = fetch(BASE_URL + "crawl-data/" + crawlId + "/warc.paths.gz");

        List<String> all;

        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {

            all = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }

        List<String> chosen = new ArrayList<>();

        for (int i = start; i < Math.min(all.size(), start + count); i++) {

            chosen.add(BASE_URL + all.get(i));
        }

        return chosen;
    }

    private static byte[] fetch(String url) throws IOException {

        try {

            HttpResponse<byte[]> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {

                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }

            return response.body();

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IOException("Interrupted fetching " + url, e);
        }
    }

    private static HttpRequest request(String url) {

        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).GET().build();
    }

    private static InputStream open(String location) throws IOException {

        if (location.startsWith("http://") || location.startsWith("https://")) {

            try {

                HttpResponse<InputStream> response = HTTP.send(request(location), HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {

                    response.body().close();

                    throw new IOException("HTTP " + response.statusCode() + " for " + location);
                }

                return response.body();

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                throw new IOException("Interrupted opening " + location, e);
            }
        }

        return Files.newInputStream(Path.of(location));
    }

    /**
     * HTML records of every file, back to back. Close the iterator to release
     * the open stream.
     */
    public Records records() {

        return new Records();
    }

    public final class Records implements Iterator<WarcReader.Record>, AutoCloseable {

        private final Iterator<String> files = locations.iterator();

        private WarcReader current;

        private int fileNumber;

        private boolean failed;

        /**
         * Whether any file ended with a read error (so its pages may be incomplete).
         */
        public boolean failed() {

            return failed;
        }

        @Override
        public boolean hasNext() {

            while (true) {

                if (current != null) {

                    try {

                        if (current.hasNext()) {

                            return true;
                        }

                    } catch (UncheckedIOException e) {

                        failed = true;

                        log.accept("  WARC stream failed, moving on: " + e.getCause().getMessage());
                    }

                    close();
                }

                if (!files.hasNext()) {

                    return false;
                }

                String location = files.next();

                fileNumber++;

                log.accept(String.format("  reading WARC %d/%d: %s", fileNumber, locations.size(),
                        location.substring(location.lastIndexOf('/') + 1)));

                try {

                    current = new WarcReader(open(location), location.endsWith(".gz"));

                } catch (IOException e) {

                    failed = true;

                    log.accept("  cannot open " + location + ": " + e.getMessage());
                }
            }
        }

        @Override
        public WarcReader.Record next() {

            if (!hasNext()) {

                throw new NoSuchElementException();
            }

            return current.next();
        }

        @Override
        public void close() {

            if (current != null) {

                try {

                    current.close();

                } catch (IOException ignored) {
                    // Nothing useful to do with a failed close of a read stream.
                }

                current = null;
            }
        }
    }

    /**
     * Parse, extract and filter one record. Thread-safe.
     *
     * @return the page as a document, or {@code null} if it should not be indexed
     */
    public static Document toDocument(WarcReader.Record record) {

        HtmlExtractor.Page page;

        try {

            org.jsoup.nodes.Document html = Jsoup.parse(new ByteArrayInputStream(record.html()), record.charset(), record.url());

            page = HtmlExtractor.extract(html, record.url());

        } catch (IOException | RuntimeException e) {

            STATS.incrementAndGet(4);

            return null;
        }

        if (page == null) {

            STATS.incrementAndGet(4);

            return null;
        }

        PageFilter.Verdict verdict = PageFilter.judge(page);

        STATS.incrementAndGet(verdict.ordinal());

        return verdict == PageFilter.Verdict.ACCEPT ? page.toDocument() : null;
    }

    /**
     * Counts of filtering outcomes so far, for progress reporting.
     */
    public static String filterSummary() {

        return String.format("kept %,d; dropped %,d non-English, %,d thin, %,d adult, %,d unparseable",
                STATS.get(0), STATS.get(1), STATS.get(2), STATS.get(3), STATS.get(4));
    }
}
