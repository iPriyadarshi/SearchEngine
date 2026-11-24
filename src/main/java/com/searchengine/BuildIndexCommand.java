package com.searchengine;

import com.searchengine.api.DocumentSource;
import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import com.searchengine.document.source.HtmlDocumentSource;
import com.searchengine.document.source.WikipediaDumpSource;
import com.searchengine.index.disk.DiskIndexWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * {@code --build-index}: streams a document source into an on-disk index.
 *
 * <pre>
 * --build-index --out &lt;dir&gt; (--wiki &lt;dump&gt; | --folder &lt;dir&gt; | --html &lt;dir&gt;)
 *               [--max-docs n] [--mem-mb n]
 * </pre>
 *
 * {@code --mem-mb} is the postings buffer budget before a segment is flushed
 * (default: a third of the max heap).
 */
final class BuildIndexCommand {

    private BuildIndexCommand() {
    }

    static void run(String[] args) throws IOException {

        Path out = null;

        DocumentSource source = null;

        Path wikiDump = null;

        long maxDocs = Long.MAX_VALUE;

        long memoryBudget = Runtime.getRuntime().maxMemory() / 3;

        for (int i = 0; i < args.length; i++) {

            String flag = args[i];

            if (i + 1 >= args.length) {

                throw usage("missing value for " + flag);
            }

            String value = args[++i];

            switch (flag) {

                case "--out" -> out = Path.of(value);

                case "--wiki" -> wikiDump = Path.of(value);

                case "--folder" -> source = new FolderDocumentSource(value);

                case "--html" -> source = new HtmlDocumentSource(value);

                case "--max-docs" -> maxDocs = Long.parseLong(value);

                case "--mem-mb" -> memoryBudget = Long.parseLong(value) << 20;

                default -> throw usage("unknown option " + flag);
            }
        }

        if (wikiDump != null) {

            source = new WikipediaDumpSource(wikiDump, maxDocs);

            maxDocs = Long.MAX_VALUE;
        }

        if (out == null || source == null) {

            throw usage("--out and one source (--wiki, --folder, --html) are required");
        }

        System.out.printf("Building index in %s (postings buffer %,d MB)%n", out, memoryBudget >> 20);

        long start = System.nanoTime();

        try (Stream<Document> docs = source.stream();
             DiskIndexWriter writer = new DiskIndexWriter(out, Main.buildParser(), memoryBudget, System.out::println)) {

            Iterator<Document> it = docs.iterator();

            for (long n = 0; n < maxDocs && it.hasNext(); n++) {

                writer.add(it.next());
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
                + " [--max-docs n] [--mem-mb n]");
    }
}
