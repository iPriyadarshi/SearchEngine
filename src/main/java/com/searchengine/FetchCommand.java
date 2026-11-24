package com.searchengine;

import com.searchengine.document.model.Document;
import com.searchengine.web.CommonCrawlSource;
import com.searchengine.web.Corpus;
import com.searchengine.web.WarcReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code --fetch-cc}: downloads Common Crawl WARC files and keeps only the
 * extracted, filtered pages as a local corpus (one {@code .docs.gz} per WARC).
 *
 * <pre>
 * --fetch-cc --cc &lt;crawl&gt; --cc-files n [--cc-start k] --corpus &lt;dir&gt;
 *            [--parallel-files p] [--threads t]
 * </pre>
 *
 * Several WARC files stream at once (network-bound) while a shared worker
 * pool parses and filters pages (CPU-bound). The command is resumable: a WARC
 * whose corpus file already exists is skipped, and a file that fails is
 * retried, then left for the next run.
 */
final class FetchCommand {

    private static final int BATCH = 64;

    private static final int ATTEMPTS = 3;

    private FetchCommand() {
    }

    static void run(String[] args) throws Exception {

        String crawl = null;

        int files = 1;

        int start = 0;

        Path corpus = null;

        int parallelFiles = 4;

        int threads = Runtime.getRuntime().availableProcessors();

        for (int i = 0; i + 1 < args.length; i += 2) {

            switch (args[i]) {

                case "--cc" -> crawl = args[i + 1];

                case "--cc-files" -> files = Integer.parseInt(args[i + 1]);

                case "--cc-start" -> start = Integer.parseInt(args[i + 1]);

                case "--corpus" -> corpus = Path.of(args[i + 1]);

                case "--parallel-files" -> parallelFiles = Integer.parseInt(args[i + 1]);

                case "--threads" -> threads = Integer.parseInt(args[i + 1]);

                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (crawl == null || corpus == null) {

            throw new IllegalArgumentException("usage: --fetch-cc --cc <crawl> --cc-files n [--cc-start k] --corpus <dir>"
                    + " [--parallel-files p] [--threads t]");
        }

        Files.createDirectories(corpus);

        List<String> todo = new ArrayList<>();

        for (String warc : CommonCrawlSource.crawlFiles(crawl, start, files)) {

            if (!Files.exists(corpus.resolve(Corpus.nameFor(warc)))) {

                todo.add(warc);
            }
        }

        System.out.printf("Fetching %d of %d WARC files into %s (%d at a time, %d extraction threads)%n",
                todo.size(), files, corpus, parallelFiles, threads);

        ExecutorService extractors = Executors.newFixedThreadPool(threads);

        ExecutorService downloads = Executors.newFixedThreadPool(parallelFiles);

        AtomicInteger finished = new AtomicInteger();

        AtomicLong pages = new AtomicLong();

        long startNanos = System.nanoTime();

        Path dir = corpus;

        int workers = threads;

        for (String warc : todo) {

            downloads.submit(() -> {

                for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {

                    try {

                        int kept = fetchOne(warc, dir.resolve(Corpus.nameFor(warc)), extractors, workers);

                        pages.addAndGet(kept);

                        double minutes = (System.nanoTime() - startNanos) / 60e9;

                        System.out.printf("  [%d/%d] %s: %,d pages (%,d total, %.1f min)%n",
                                finished.incrementAndGet(), todo.size(), Corpus.nameFor(warc), kept, pages.get(), minutes);

                        return;

                    } catch (Exception e) {

                        System.out.printf("  %s failed (attempt %d/%d): %s%n", Corpus.nameFor(warc), attempt, ATTEMPTS, e);
                    }
                }
            });
        }

        downloads.shutdown();

        downloads.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);

        extractors.shutdown();

        System.out.printf("Done: %,d pages in %.1f min. %s%n",
                pages.get(), (System.nanoTime() - startNanos) / 60e9, CommonCrawlSource.filterSummary());
    }

    /**
     * Stream one WARC, extract its pages on the shared pool, and write them in
     * file order to a corpus file.
     */
    private static int fetchOne(String warc, Path target, ExecutorService extractors, int threads)
            throws IOException, InterruptedException, ExecutionException {

        CommonCrawlSource source = new CommonCrawlSource(List.of(warc), message -> { });

        try (CommonCrawlSource.Records records = source.records(); Corpus.Writer writer = new Corpus.Writer(target)) {

            Deque<Future<List<Document>>> pending = new ArrayDeque<>();

            List<WarcReader.Record> batch = new ArrayList<>(BATCH);

            while (records.hasNext()) {

                batch.add(records.next());

                if (batch.size() == BATCH) {

                    pending.add(submit(extractors, batch));

                    batch = new ArrayList<>(BATCH);

                    while (pending.size() > threads) {

                        write(writer, pending.poll().get());
                    }
                }
            }

            if (!batch.isEmpty()) {

                pending.add(submit(extractors, batch));
            }

            while (!pending.isEmpty()) {

                write(writer, pending.poll().get());
            }

            if (records.failed()) {

                throw new IOException("stream for " + warc + " ended early");
            }

            writer.commit();

            return writer.pages();

        } catch (UncheckedIOException e) {

            throw e.getCause();
        }
    }

    private static Future<List<Document>> submit(ExecutorService pool, List<WarcReader.Record> batch) {

        return pool.submit(() -> {

            List<Document> docs = new ArrayList<>(batch.size());

            for (WarcReader.Record record : batch) {

                Document doc = CommonCrawlSource.toDocument(record);

                if (doc != null) {

                    docs.add(doc);
                }
            }

            return docs;
        });
    }

    private static void write(Corpus.Writer writer, List<Document> docs) throws IOException {

        for (Document doc : docs) {

            writer.write(doc);
        }
    }
}
