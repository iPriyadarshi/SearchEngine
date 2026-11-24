package com.searchengine;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.query.disk.PhraseSearcher;
import com.searchengine.query.disk.Similarity;
import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopKSearcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * {@code --bench}: measures query latency and throughput on a disk index.
 *
 * <pre>
 * --bench --index &lt;dir&gt; [--queries n] [--threads n] [--k n]
 * </pre>
 *
 * Queries are realistic: titles of randomly chosen documents (1-4 words),
 * run as BM25 top-k and as phrase queries. Every query set runs once to warm
 * the page cache before it is timed. Also reports how much work MaxScore
 * avoided: documents scored versus the sum of the query terms' postings.
 */
final class BenchmarkCommand {

    private BenchmarkCommand() {
    }

    static void run(String[] args) throws Exception {

        Path dir = null;

        int queries = 1000;

        int threads = Runtime.getRuntime().availableProcessors();

        int k = 10;

        for (int i = 0; i + 1 < args.length; i += 2) {

            switch (args[i]) {

                case "--index" -> dir = Path.of(args[i + 1]);

                case "--queries" -> queries = Integer.parseInt(args[i + 1]);

                case "--threads" -> threads = Integer.parseInt(args[i + 1]);

                case "--k" -> k = Integer.parseInt(args[i + 1]);

                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (dir == null) {

            throw new IllegalArgumentException("usage: --bench --index <dir> [--queries n] [--threads n] [--k n]");
        }

        Parser parser = Main.buildParser();

        try (DiskIndex index = DiskIndex.open(dir)) {

            System.out.printf("Index: %,d docs, %,d terms, %,d tokens%n",
                    index.numDocs(), index.numTerms(), index.totalTokens());

            List<List<String>> titleQueries = sampleTitleQueries(index, parser, queries, new Random(42));

            TopKSearcher topK = new TopKSearcher(index);

            PhraseSearcher phrase = new PhraseSearcher(index);

            int topN = k;

            measure("BM25 top-" + k, titleQueries, threads, q -> topK.search(q, Similarity.bm25(), topN), index);

            measure("TF-IDF top-" + k, titleQueries, threads, q -> topK.search(q, Similarity.tfIdf(), topN), index);

            List<List<String>> phrases = titleQueries.stream().filter(q -> q.size() >= 2).toList();

            measure("Phrase", phrases, threads, q -> phrase.search(q, topN), index);
        }
    }

    /**
     * Analyzed titles of random documents, keeping those with 1-4 known terms.
     */
    private static List<List<String>> sampleTitleQueries(DiskIndex index, Parser parser, int count, Random random) {

        List<List<String>> result = new ArrayList<>();

        for (int attempts = 0; result.size() < count && attempts < count * 20; attempts++) {

            String title = index.document(random.nextInt(index.numDocs())).title();

            List<String> terms = parser.parse(new Document(-1, title, "<query>"));

            if (!terms.isEmpty() && terms.size() <= 4 && terms.stream().allMatch(t -> index.termInfo(t) != null)) {

                result.add(terms);
            }
        }

        return result;
    }

    private static void measure(String name, List<List<String>> queries, int threads,
                                Function<List<String>, TopDocs> search, DiskIndex index) throws Exception {

        // Warm-up pass (page cache, JIT).
        for (List<String> q : queries) {

            search.apply(q);
        }

        long[] nanos = new long[queries.size()];

        long scored = 0;

        long postings = 0;

        for (int i = 0; i < queries.size(); i++) {

            List<String> q = queries.get(i);

            long start = System.nanoTime();

            TopDocs top = search.apply(q);

            nanos[i] = System.nanoTime() - start;

            scored += top.scored();

            for (String term : new LinkedHashSet<>(q)) {

                postings += index.docFreq(term);
            }
        }

        Arrays.sort(nanos);

        double wallSeconds = parallelWallSeconds(queries, threads, search);

        System.out.printf("%n%s (%,d queries)%n", name, queries.size());

        System.out.printf("  latency  p50 %.2f ms   p90 %.2f ms   p99 %.2f ms   max %.2f ms%n",
                pct(nanos, 50), pct(nanos, 90), pct(nanos, 99), nanos[nanos.length - 1] / 1e6);

        System.out.printf("  throughput  %,.0f q/s on 1 thread, %,.0f q/s on %d threads%n",
                queries.size() / (Arrays.stream(nanos).sum() / 1e9), queries.size() / wallSeconds, threads);

        System.out.printf("  work  scored %,d docs of %,d postings (%.1f%%)%n",
                scored, postings, postings == 0 ? 0.0 : 100.0 * scored / postings);
    }

    private static double parallelWallSeconds(List<List<String>> queries, int threads,
                                              Function<List<String>, TopDocs> search) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {

            long start = System.nanoTime();

            List<Future<TopDocs>> futures = new ArrayList<>();

            for (List<String> q : queries) {

                futures.add(pool.submit(() -> search.apply(q)));
            }

            for (Future<TopDocs> future : futures) {

                future.get();
            }

            return (System.nanoTime() - start) / 1e9;

        } finally {

            pool.shutdownNow();
        }
    }

    private static double pct(long[] sorted, int percentile) {

        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);

        return sorted[Math.max(0, index)] / 1e6;
    }
}
