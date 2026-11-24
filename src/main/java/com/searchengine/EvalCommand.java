package com.searchengine;

import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.StoredDocument;
import com.searchengine.query.disk.Similarity;
import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopKSearcher;
import com.searchengine.search.WebRanker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * {@code --eval}: measures ranking quality on a set of judged queries.
 *
 * <pre>
 * --eval --index &lt;dir&gt; [--queries eval/web-queries.tsv] [--verbose] [--tune]
 * </pre>
 *
 * Query file: one query per line, a tab, then the acceptable answers
 * separated by {@code |}. An answer is a URL prefix without scheme or
 * "www." ({@code python.org}, {@code en.wikipedia.org/wiki/Black_hole}).
 * Metrics, over the top 10: MRR (mean reciprocal rank of the first correct
 * answer), Success@1 and Success@10. Queries whose answers are not in the
 * index at all are reported separately and left out: they measure coverage,
 * not ranking.
 * <p>
 * {@code --tune} runs coordinate ascent over {@link WebRanker.Weights} on half
 * the queries and reports the other half, so the gain is not just overfitting.
 */
final class EvalCommand {

    static final int K = 10;

    record Judged(String query, List<String> answers) {
    }

    record Metrics(double mrr, double success1, double success10, int queries) {

        @Override
        public String toString() {

            return String.format(Locale.ROOT, "MRR@10 %.3f   Success@1 %.3f   Success@10 %.3f   (%d queries)",
                    mrr, success1, success10, queries);
        }
    }

    private EvalCommand() {
    }

    static void run(String[] args) throws IOException {

        Path dir = null;

        Path queries = Path.of("eval/web-queries.tsv");

        boolean verbose = false;

        boolean tune = false;

        for (int i = 0; i < args.length; i++) {

            switch (args[i]) {

                case "--index" -> dir = Path.of(args[++i]);

                case "--queries" -> queries = Path.of(args[++i]);

                case "--verbose" -> verbose = true;

                case "--tune" -> tune = true;

                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (dir == null) {

            throw new IllegalArgumentException("usage: --eval --index <dir> [--queries <tsv>] [--verbose] [--tune]");
        }

        List<Judged> all = read(queries);

        try (DiskIndex index = DiskIndex.open(dir)) {

            WebRanker ranker = new WebRanker(index, Main.buildParser(), WebRanker.Weights.defaults());

            TopKSearcher body = new TopKSearcher(index);

            List<Judged> judged = new ArrayList<>();

            List<String> uncovered = new ArrayList<>();

            for (Judged j : all) {

                if (covered(index, j)) {

                    judged.add(j);

                } else {

                    uncovered.add(j.query());
                }
            }

            System.out.printf("%d of %d queries have an answer in the index.%n", judged.size(), all.size());

            if (!uncovered.isEmpty()) {

                System.out.println("Not in the index (coverage gaps): " + String.join(", ", uncovered));
            }

            Function<String, TopDocs> baseline = q -> body.search(analyze(q), Similarity.bm25(), K);

            Function<String, TopDocs> web = q -> ranker.search(q, K);

            System.out.println();

            System.out.println("Body BM25 only:   " + evaluate(index, judged, baseline, false));

            System.out.println("Web ranker:       " + evaluate(index, judged, web, verbose));

            if (tune) {

                tune(index, judged, ranker);
            }
        }
    }

    private static List<String> analyze(String text) {

        return Main.buildParser().parse(new com.searchengine.document.model.Document(-1, text, ""));
    }

    static List<Judged> read(Path file) throws IOException {

        List<Judged> judged = new ArrayList<>();

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {

            if (line.isBlank() || line.startsWith("#")) {

                continue;
            }

            String[] parts = line.split("\t");

            if (parts.length < 2) {

                throw new IOException("Expected 'query<TAB>answer|answer' in: " + line);
            }

            List<String> answers = new ArrayList<>();

            for (String answer : parts[1].split("\\|")) {

                answers.add(canonical(answer));
            }

            judged.add(new Judged(parts[0].strip(), answers));
        }

        return judged;
    }

    static String canonical(String url) {

        String u = url.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");

        return u;
    }

    static boolean isAnswer(String url, List<String> answers) {

        String u = canonical(url);

        for (String answer : answers) {

            if (u.startsWith(answer)) {

                return true;
            }
        }

        return false;
    }

    /**
     * Whether any indexed page matches an answer (scanned once per query set).
     */
    private static boolean covered(DiskIndex index, Judged judged) {

        if (coverage == null) {

            coverage = new java.util.TreeSet<>();

            for (int d = 0; d < index.numDocs(); d++) {

                coverage.add(canonical(index.document(d).path()));
            }
        }

        for (String answer : judged.answers()) {

            // The smallest indexed URL >= the answer starts with it, if any does.
            String next = coverage.ceiling(answer);

            if (next != null && next.startsWith(answer)) {

                return true;
            }
        }

        return false;
    }

    private static java.util.TreeSet<String> coverage;

    static Metrics evaluate(DiskIndex index, List<Judged> judged, Function<String, TopDocs> search, boolean verbose) {

        double mrr = 0;

        int at1 = 0;

        int at10 = 0;

        for (Judged j : judged) {

            List<TopDocs.ScoredDoc> hits = search.apply(j.query()).hits();

            int rank = 0;

            for (int i = 0; i < hits.size() && i < K; i++) {

                if (isAnswer(index.document(hits.get(i).docId()).path(), j.answers())) {

                    rank = i + 1;

                    break;
                }
            }

            if (rank > 0) {

                mrr += 1.0 / rank;

                at10++;

                if (rank == 1) {

                    at1++;
                }
            }

            if (verbose && rank != 1) {

                StoredDocument top = hits.isEmpty() ? null : index.document(hits.getFirst().docId());

                System.out.printf("  %-34s rank %-4s top: %s%n", j.query(), rank == 0 ? ">10" : rank,
                        top == null ? "(none)" : top.path());
            }
        }

        int n = Math.max(1, judged.size());

        return new Metrics(mrr / n, (double) at1 / n, (double) at10 / n, judged.size());
    }

    /**
     * Coordinate ascent on even-numbered queries; report odd-numbered ones.
     */
    private static void tune(DiskIndex index, List<Judged> judged, WebRanker ranker) {

        List<Judged> train = new ArrayList<>();

        List<Judged> test = new ArrayList<>();

        for (int i = 0; i < judged.size(); i++) {

            (i % 2 == 0 ? train : test).add(judged.get(i));
        }

        Function<String, TopDocs> search = q -> ranker.search(q, K);

        WebRanker.Weights best = ranker.weights();

        double bestScore = evaluate(index, train, search, false).mrr();

        System.out.printf("%nTuning on %d queries (held out: %d). Start: train MRR %.3f, held-out %s%n",
                train.size(), test.size(), bestScore, evaluate(index, test, search, false));

        double[] factors = {0, 0.25, 0.5, 2, 4};

        for (int round = 0; round < 3; round++) {

            boolean improved = false;

            for (int w = 0; w < WebRanker.Weights.NAMES.length; w++) {

                double current = best.toArray()[w];

                for (double factor : factors) {

                    double value = current == 0 ? factor : current * factor;

                    WebRanker.Weights candidate = best.with(w, value);

                    ranker.setWeights(candidate);

                    double score = evaluate(index, train, search, false).mrr();

                    if (score > bestScore + 1e-9) {

                        bestScore = score;

                        best = candidate;

                        improved = true;
                    }
                }

                ranker.setWeights(best);
            }

            if (!improved) {

                break;
            }
        }

        ranker.setWeights(best);

        System.out.println("Tuned weights:    " + best);

        System.out.println("Train:            " + evaluate(index, train, search, false));

        System.out.println("Held-out:         " + evaluate(index, test, search, false));
    }
}
