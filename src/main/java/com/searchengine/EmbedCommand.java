package com.searchengine;

import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.StoredDocument;
import com.searchengine.semantic.Embedder;
import com.searchengine.semantic.VectorStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * {@code --embed}: computes a semantic vector for every document of an
 * existing disk index, enabling semantic (meaning-based) retrieval.
 *
 * <pre>
 * --embed --index &lt;dir&gt; --model &lt;dir&gt; [--max-tokens n] [--batch n] [--threads n]
 * </pre>
 *
 * The text embedded per document is its title and stored leading text
 * (meta description first, about 600 characters).
 */
final class EmbedCommand {

    private EmbedCommand() {
    }

    static void run(String[] args) throws Exception {

        Path index = null;

        Path model = null;

        int maxTokens = 192;

        int batch = 32;

        int threads = Runtime.getRuntime().availableProcessors();

        for (int i = 0; i + 1 < args.length; i += 2) {

            switch (args[i]) {

                case "--index" -> index = Path.of(args[i + 1]);

                case "--model" -> model = Path.of(args[i + 1]);

                case "--max-tokens" -> maxTokens = Integer.parseInt(args[i + 1]);

                case "--batch" -> batch = Integer.parseInt(args[i + 1]);

                case "--threads" -> threads = Integer.parseInt(args[i + 1]);

                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (index == null || model == null) {

            throw new IllegalArgumentException("usage: --embed --index <dir> --model <dir> [--max-tokens n] [--batch n] [--threads n]");
        }

        long start = System.nanoTime();

        try (DiskIndex disk = DiskIndex.open(index);
             Embedder embedder = Embedder.load(model, threads);
             VectorStore.Writer writer = new VectorStore.Writer(index, disk.numDocs(), embedder.dimensions())) {

            int n = disk.numDocs();

            System.out.printf("Embedding %,d documents with %s (%d dimensions, up to %d tokens each)%n",
                    n, model.getFileName(), embedder.dimensions(), maxTokens);

            List<String> texts = new ArrayList<>(batch);

            for (int d = 0; d < n; d++) {

                StoredDocument doc = disk.document(d);

                texts.add(doc.title().isEmpty() ? doc.text() : doc.title() + "\n" + doc.text());

                if (texts.size() == batch || d == n - 1) {

                    for (float[] vector : embedder.embedDocuments(texts, maxTokens)) {

                        writer.write(vector);
                    }

                    texts.clear();
                }

                if ((d + 1) % 5_000 == 0) {

                    double seconds = (System.nanoTime() - start) / 1e9;

                    System.out.printf("  %,d / %,d (%,.0f docs/s, ~%.0f min left)%n",
                            d + 1, n, (d + 1) / seconds, (n - d - 1) / ((d + 1) / seconds) / 60);
                }
            }

            Properties settings = new Properties();

            settings.setProperty("model", model.toAbsolutePath().toString());

            settings.setProperty("dimensions", String.valueOf(embedder.dimensions()));

            settings.setProperty("maxTokens", String.valueOf(maxTokens));

            settings.setProperty("documents", String.valueOf(n));

            writer.commit(settings);
        }

        System.out.printf("Done in %.1f min.%n", (System.nanoTime() - start) / 60e9);
    }
}
