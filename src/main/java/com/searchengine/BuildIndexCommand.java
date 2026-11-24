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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code --build-index}: streams a document source into an on-disk index.
 *
 * <pre>
 * --build-index --out &lt;dir&gt; (--wiki &lt;dump&gt; | --folder &lt;dir&gt; | --html &lt;dir&gt;)
 *               [--max-docs n] [--mem-mb n] [--threads n]
 * </pre>
 *
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

        Path wikiDump = null;

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

                case "--wiki" -> wikiDump = Path.of(value);

                case "--folder" -> folder = value;

                case "--html" -> html = value;

                case "--max-docs" -> maxDocs = Long.parseLong(value);

                case "--mem-mb" -> memoryBudget = Long.parseLong(value) << 20;

                case "--threads" -> threads = Math.max(1, Integer.parseInt(value));

                default -> throw usage("unknown option " + flag);
            }
        }

        if (out == null || (wikiDump == null && folder == null && html == null)) {

            throw usage("--out and one source (--wiki, --folder, --html) are required");
        }

        System.out.printf("Building index in %s (postings buffer %,d MB, %d threads)%n",
                out, memoryBudget >> 20, threads);

        long start = System.nanoTime();

        Parser parser = Main.buildParser();

        try (DiskIndexWriter writer = new DiskIndexWriter(out, parser, memoryBudget, System.out::println)) {

            if (wikiDump != null) {

                // Cleaning wikitext is the expensive per-page step, so it runs on the workers.
                WikipediaDumpSource source = new WikipediaDumpSource(wikiDump, Long.MAX_VALUE, threads);

                try (Stream<WikiPage> pages = source.rawPages()) {

                    ParallelIndexer.index(pages.iterator(), WikipediaDumpSource::toDocument,
                            parser, writer, threads, maxDocs);
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
                + "usage: --build-index --out <dir> (--wiki <dump> | --folder <dir> | --html <dir>)"
                + " [--max-docs n] [--mem-mb n] [--threads n]");
    }
}
