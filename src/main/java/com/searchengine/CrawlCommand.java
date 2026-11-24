package com.searchengine;

import com.searchengine.document.model.Document;
import com.searchengine.web.Corpus;
import com.searchengine.web.HttpFetcher;
import com.searchengine.web.PoliteCrawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code --crawl-web}: politely crawls a list of sites into a corpus.
 *
 * <pre>
 * --crawl-web --seeds &lt;file&gt; --corpus &lt;dir&gt; [--top n] [--skip k]
 *             [--sites-parallel p] [--pages-per-site m]
 * </pre>
 *
 * The seed file is either a Tranco-style "rank,domain" CSV (each domain's
 * home page is the start URL) or one URL per line. Sites are crawled in
 * parallel; each site gets one connection with delays (see
 * {@link PoliteCrawler}). Pages go to rotating corpus files; a site is
 * recorded as done only once its pages are committed, so the crawl can be
 * stopped and resumed.
 */
final class CrawlCommand {

    private static final int PAGES_PER_FILE = 10_000;

    private CrawlCommand() {
    }

    static void run(String[] args) throws Exception {

        Path seeds = null;

        Path corpus = null;

        int top = 10_000;

        int skip = 0;

        int parallel = 64;

        PoliteCrawler.Options defaults = PoliteCrawler.Options.defaults();

        int pagesPerSite = defaults.maxPagesPerSite();

        for (int i = 0; i + 1 < args.length; i += 2) {

            switch (args[i]) {

                case "--seeds" -> seeds = Path.of(args[i + 1]);

                case "--corpus" -> corpus = Path.of(args[i + 1]);

                case "--top" -> top = Integer.parseInt(args[i + 1]);

                case "--skip" -> skip = Integer.parseInt(args[i + 1]);

                case "--sites-parallel" -> parallel = Integer.parseInt(args[i + 1]);

                case "--pages-per-site" -> pagesPerSite = Integer.parseInt(args[i + 1]);

                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (seeds == null || corpus == null) {

            throw new IllegalArgumentException("usage: --crawl-web --seeds <file> --corpus <dir> [--top n] [--skip k]"
                    + " [--sites-parallel p] [--pages-per-site m]");
        }

        Files.createDirectories(corpus);

        Path doneLog = corpus.resolve("sites-done.txt");

        Set<String> done = new HashSet<>();

        if (Files.exists(doneLog)) {

            done.addAll(Files.readAllLines(doneLog));
        }

        List<String> sites = new ArrayList<>();

        for (String site : readSeeds(seeds, skip, top)) {

            if (!done.contains(site)) {

                sites.add(site);
            }
        }

        System.out.printf("Crawling %,d sites (%,d already done) into %s: %d at a time, up to %d pages each%n",
                sites.size(), done.size(), corpus, parallel, pagesPerSite);

        PoliteCrawler crawler = new PoliteCrawler(new HttpFetcher(), new PoliteCrawler.Options(
                pagesPerSite, defaults.minDelayMillis(), defaults.maxDelayMillis(), defaults.maxPageBytes()));

        ExecutorService pool = Executors.newFixedThreadPool(parallel);

        AtomicInteger finished = new AtomicInteger();

        AtomicLong pages = new AtomicLong();

        long start = System.nanoTime();

        try (RotatingCorpus out = new RotatingCorpus(corpus, doneLog)) {

            for (String site : sites) {

                pool.submit(() -> {

                    try {

                        int count = crawler.crawlSite("https://" + site + "/", out::write);

                        pages.addAndGet(count);

                        out.siteDone(site);

                        int n = finished.incrementAndGet();

                        if (n % 100 == 0) {

                            System.out.printf("  %,d/%,d sites, %,d pages (%.1f min)%n",
                                    n, sites.size(), pages.get(), (System.nanoTime() - start) / 60e9);
                        }

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();

                    } catch (RuntimeException e) {

                        System.out.println("  " + site + " failed: " + e);
                    }
                });
            }

            pool.shutdown();

            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        }

        System.out.printf("Done: %,d pages from %,d sites in %.1f min%n",
                pages.get(), finished.get(), (System.nanoTime() - start) / 60e9);
    }

    /**
     * Site host names from a "rank,domain" CSV or a URL-per-line file.
     */
    static List<String> readSeeds(Path file, int skip, int top) throws IOException {

        List<String> sites = new ArrayList<>();

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {

            String value = line.strip();

            if (value.isEmpty() || value.startsWith("#")) {

                continue;
            }

            if (value.contains(",")) {

                value = value.substring(value.indexOf(',') + 1).strip();
            }

            value = value.replaceFirst("^https?://", "").replaceFirst("/.*$", "").toLowerCase(Locale.ROOT);

            if (!value.isEmpty()) {

                sites.add(value);
            }
        }

        return sites.subList(Math.min(skip, sites.size()), Math.min(sites.size(), skip + top));
    }

    /**
     * Thread-safe corpus output in files of {@value #PAGES_PER_FILE} pages.
     * Sites finished since the last commit are logged when the file commits.
     */
    private static final class RotatingCorpus implements AutoCloseable {

        private final Path dir;

        private final Path doneLog;

        private final List<String> pendingSites = new ArrayList<>();

        private Corpus.Writer writer;

        private int fileNumber;

        RotatingCorpus(Path dir, Path doneLog) throws IOException {

            this.dir = dir;

            this.doneLog = doneLog;

            this.fileNumber = Corpus.files(dir).size();
        }

        synchronized void write(Document page) throws IOException {

            if (writer == null) {

                writer = new Corpus.Writer(dir.resolve(String.format("crawl-%05d%s", ++fileNumber, Corpus.SUFFIX)));
            }

            writer.write(page);

            if (writer.pages() >= PAGES_PER_FILE) {

                commit();
            }
        }

        synchronized void siteDone(String site) {

            pendingSites.add(site);
        }

        private void commit() throws IOException {

            if (writer != null) {

                writer.commit();

                writer.close();

                writer = null;
            }

            if (!pendingSites.isEmpty()) {

                Files.write(doneLog, pendingSites, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                pendingSites.clear();
            }
        }

        @Override
        public synchronized void close() throws IOException {

            commit();
        }
    }
}
