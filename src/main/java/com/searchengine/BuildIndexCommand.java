package com.searchengine;

import com.searchengine.api.DocumentSource;
import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import com.searchengine.document.source.HtmlDocumentSource;
import com.searchengine.document.source.WikipediaDumpSource;
import com.searchengine.document.source.WikipediaDumpSource.WikiPage;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.index.disk.ParallelIndexer;
import com.searchengine.web.CommonCrawlSource;
import com.searchengine.web.Corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code --build-index}: streams a document source into an on-disk index.
 *
 * <pre>
 * --build-index --out &lt;dir&gt; (--wiki &lt;dump|dir&gt;... | --warc &lt;file|url&gt;... | --cc &lt;crawl&gt; [--cc-files n] [--cc-start k]
 *                                | --folder &lt;dir&gt; | --html &lt;dir&gt;)
 *               [--max-docs n] [--mem-mb n] [--threads n]
 * </pre>
 *
 * {@code --cc CC-MAIN-2026-47 --cc-files 30} streams 30 WARC files of that
 * Common Crawl release from data.commoncrawl.org without storing them;
 * {@code --warc} takes local WARC files or URLs.
 * <p>
 * {@code --wiki} may be repeated, and may name a directory: every
 * {@code .bz2}/{@code .xml}/{@code .gz} dump in it is indexed in part order
 * (e.g. the 27 {@code enwiki-...-multistreamN.xml-pA pB.bz2} part files).
 *
 * {@code --domain-ranks top-1m.csv} records each page's site popularity
 * (Tranco "rank,domain" format) as a ranking signal.
 * <p>
 * {@code --mem-mb} is the postings buffer budget before a segment is flushed
 * (default: a third of the max heap). {@code --threads} sizes both the
 * analysis pool and, for multistream bzip2 dumps, the decompression pool
 * (default: available processors).
 */
final class BuildIndexCommand {

    private BuildIndexCommand() {
    }

    static void run(String[] args) throws IOException {

        Path out = null;

        String folder = null;

        String html = null;

        List<Path> wikiDumps = new ArrayList<>();

        List<String> warcs = new ArrayList<>();

        Path corpus = null;

        Path domainRankFile = null;

        String ccCrawl = null;

        int ccFiles = 1;

        int ccStart = 0;

        long maxDocs = Long.MAX_VALUE;

        long memoryBudget = Runtime.getRuntime().maxMemory() / 3;

        int threads = Runtime.getRuntime().availableProcessors();

        for (int i = 0; i < args.length; i++) {

            String flag = args[i];

            if (i + 1 >= args.length) {

                throw usage("missing value for " + flag);
            }

            String value = args[++i];

            switch (flag) {

                case "--out" -> out = Path.of(value);

                case "--wiki" -> wikiDumps.addAll(dumpFiles(Path.of(value)));

                case "--corpus" -> corpus = Path.of(value);

                case "--domain-ranks" -> domainRankFile = Path.of(value);

                case "--warc" -> warcs.add(value);

                case "--cc" -> ccCrawl = value;

                case "--cc-files" -> ccFiles = Integer.parseInt(value);

                case "--cc-start" -> ccStart = Integer.parseInt(value);

                case "--folder" -> folder = value;

                case "--html" -> html = value;

                case "--max-docs" -> maxDocs = Long.parseLong(value);

                case "--mem-mb" -> memoryBudget = Long.parseLong(value) << 20;

                case "--threads" -> threads = Math.max(1, Integer.parseInt(value));

                default -> throw usage("unknown option " + flag);
            }
        }

        if (ccCrawl != null) {

            warcs.addAll(CommonCrawlSource.crawlFiles(ccCrawl, ccStart, ccFiles));
        }

        if (out == null || (corpus == null && wikiDumps.isEmpty() && warcs.isEmpty() && folder == null && html == null)) {

            throw usage("--out and a source (--corpus, --wiki, --warc, --cc, --folder, --html) are required");
        }

        System.out.printf("Building index in %s (postings buffer %,d MB, %d threads)%n",
                out, memoryBudget >> 20, threads);

        long start = System.nanoTime();

        Parser parser = Main.buildParser();

        try (DiskIndexWriter writer = new DiskIndexWriter(out, parser, memoryBudget, System.out::println)) {

            // Web pages repeat across URLs and mirrors; index each only once.
            writer.setDeduplicate(corpus != null || !warcs.isEmpty());

            if (domainRankFile != null) {

                writer.setDomainRanks(readDomainRanks(domainRankFile));
            }

            if (corpus != null) {

                List<Path> files = Corpus.files(corpus);

                System.out.printf("  reading %d corpus file(s) from %s%n", files.size(), corpus);

                try (Corpus.Reader pages = Corpus.read(files)) {

                    ParallelIndexer.index(pages, Function.identity(), parser, writer, threads, maxDocs);
                }

            } else if (!warcs.isEmpty()) {

                // Parsing HTML, extraction and filtering run on the workers.
                try (CommonCrawlSource.Records records = new CommonCrawlSource(warcs, System.out::println).records()) {

                    ParallelIndexer.index(records, CommonCrawlSource::toDocument, parser, writer, threads, maxDocs);
                }

                System.out.println("  " + CommonCrawlSource.filterSummary());

            } else if (!wikiDumps.isEmpty()) {

                // Cleaning wikitext is the expensive per-page step, so it runs on the workers.
                try (DumpSequence pages = new DumpSequence(wikiDumps, threads)) {

                    ParallelIndexer.index(pages, WikipediaDumpSource::toDocument, parser, writer, threads, maxDocs);
                }

            } else {

                DocumentSource source = folder != null
                        ? new FolderDocumentSource(folder)
                        : new HtmlDocumentSource(html);

                try (Stream<Document> docs = source.stream()) {

                    ParallelIndexer.index(docs.iterator(), Function.identity(), parser, writer, threads, maxDocs);
                }
            }

        } catch (UncheckedIOException e) {

            throw e.getCause();
        }

        System.out.printf("Index ready in %.1fs, %,d MB on disk.%n",
                (System.nanoTime() - start) / 1e9, directorySize(out) >> 20);
    }

    /**
     * Tranco-style "rank,domain" CSV (https://tranco-list.eu/) into domain -> rank.
     */
    static Map<String, Integer> readDomainRanks(Path csv) throws IOException {

        Map<String, Integer> ranks = new HashMap<>(2_000_000);

        try (Stream<String> lines = Files.lines(csv)) {

            lines.forEach(line -> {

                int comma = line.indexOf(',');

                if (comma > 0) {

                    try {

                        ranks.putIfAbsent(line.substring(comma + 1).strip().toLowerCase(Locale.ROOT),
                                Integer.parseInt(line.substring(0, comma).strip()));

                    } catch (NumberFormatException ignored) {
                        // Header or malformed line.
                    }
                }
            });
        }

        System.out.printf("  loaded %,d domain ranks from %s%n", ranks.size(), csv);

        return ranks;
    }

    /**
     * A dump file, or every dump in a directory sorted by part number.
     */
    static List<Path> dumpFiles(Path path) throws IOException {

        if (!Files.isDirectory(path)) {

            return List.of(path);
        }

        try (Stream<Path> files = Files.list(path)) {

            List<Path> dumps = files
                    .filter(p -> p.getFileName().toString().matches(".*\\.(bz2|xml|gz)$"))
                    .sorted(Comparator.comparing(BuildIndexCommand::partOrder).thenComparing(Path::toString))
                    .toList();

            if (dumps.isEmpty()) {

                throw usage("no dump files (.bz2/.xml/.gz) in " + path);
            }

            return dumps;
        }
    }

    /**
     * Sort key for "...multistream12.xml-p7054860p8554859.bz2": (12, 7054860).
     */
    private static long partOrder(Path path) {

        Matcher m = PART.matcher(path.getFileName().toString());

        return m.find() ? Long.parseLong(m.group(1)) * 1_000_000_000_000L + Long.parseLong(m.group(2)) : Long.MAX_VALUE;
    }

    private static final Pattern PART = Pattern.compile("(\\d+)\\.xml-p(\\d+)");

    /**
     * Iterates the pages of several dumps back to back, opening each file only
     * when the previous one is exhausted.
     */
    private static final class DumpSequence implements Iterator<WikiPage>, AutoCloseable {

        private final Iterator<Path> files;

        private final int threads;

        private Stream<WikiPage> current;

        private Iterator<WikiPage> pages = java.util.Collections.emptyIterator();

        DumpSequence(List<Path> files, int threads) {

            this.files = files.iterator();

            this.threads = threads;
        }

        @Override
        public boolean hasNext() {

            while (!pages.hasNext()) {

                close();

                if (!files.hasNext()) {

                    return false;
                }

                Path file = files.next();

                System.out.println("  reading " + file.getFileName());

                try {

                    current = new WikipediaDumpSource(file, Long.MAX_VALUE, threads).rawPages();

                } catch (IOException e) {

                    throw new UncheckedIOException(e);
                }

                pages = current.iterator();
            }

            return true;
        }

        @Override
        public WikiPage next() {

            if (!hasNext()) {

                throw new java.util.NoSuchElementException();
            }

            return pages.next();
        }

        @Override
        public void close() {

            if (current != null) {

                current.close();

                current = null;
            }
        }
    }

    private static long directorySize(Path dir) throws IOException {

        try (Stream<Path> files = Files.list(dir)) {

            return files.mapToLong(p -> {

                try {

                    return Files.size(p);

                } catch (IOException e) {

                    return 0;
                }
            }).sum();
        }
    }

    private static IllegalArgumentException usage(String problem) {

        return new IllegalArgumentException(problem + System.lineSeparator()
                + "usage: --build-index --out <dir> (--wiki <dump|dir>... | --warc <file|url>..."
                + " | --cc <crawl> [--cc-files n] [--cc-start k] | --corpus <dir> | --folder <dir> | --html <dir>)"
                + " [--domain-ranks <csv>]"
                + " [--max-docs n] [--mem-mb n] [--threads n]");
    }
}
