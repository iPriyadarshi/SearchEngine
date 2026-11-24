package com.searchengine.search;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.index.disk.IndexFiles;
import com.searchengine.index.disk.StoredDocument;
import com.searchengine.query.disk.Similarity;
import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopDocs.ScoredDoc;
import com.searchengine.query.disk.TopKSearcher;
import com.searchengine.query.disk.TopKSearcher.WeightedTerm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Two-stage web ranking.
 * <ol>
 * <li><b>Retrieve:</b> each query term contributes BM25 clauses for the body
 * and for the title, URL and anchor-text fields (weighted), plus a clause for
 * the words run together as a URL word ("stack overflow" → "stackoverflow").
 * MaxScore returns the best {@value #CANDIDATES} candidates.</li>
 * <li><b>Re-rank:</b> each candidate's text score is combined with document
 * signals - PageRank, site popularity, how much of the query the title
 * covers, an exact title match, being the home page of a site the query
 * names, and URL depth.</li>
 * </ol>
 * The weights are a {@link Weights} record so they can be tuned against an
 * evaluation set.
 */
public final class WebRanker {

    static final int CANDIDATES = 100;

    // Field terms have no stored length, so their BM25 ignores length (b = 0).
    private static final Similarity BODY = Similarity.bm25(1.2, 0.75);

    private static final Similarity FIELD = Similarity.bm25(1.2, 0.0);

    /**
     * Field weights (relative to the body) and re-ranking weights.
     */
    public record Weights(double title, double url, double anchor,
                          double pageRank, double domain, double titleCoverage, double exactTitle,
                          double homepage, double depth) {

        public static Weights defaults() {

            return new Weights(2.0, 1.5, 1.5, 0.5, 3.0, 2.0, 2.0, 3.0, 0.3);
        }

        Weights with(int index, double value) {

            double[] v = toArray();

            v[index] = value;

            return new Weights(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
        }

        double[] toArray() {

            return new double[]{title, url, anchor, pageRank, domain, titleCoverage, exactTitle, homepage, depth};
        }

        static final String[] NAMES = {"title", "url", "anchor", "pageRank", "domain", "titleCoverage",
                "exactTitle", "homepage", "depth"};
    }

    private final DiskIndex index;

    private final Parser parser;

    private final TopKSearcher searcher;

    private volatile Weights weights;

    public WebRanker(DiskIndex index, Parser parser, Weights weights) {

        this.index = index;

        this.parser = parser;

        this.searcher = new TopKSearcher(index);

        this.weights = weights;
    }

    public Weights weights() {

        return weights;
    }

    public void setWeights(Weights weights) {

        this.weights = weights;
    }

    private List<String> analyze(String text) {

        return parser.parse(new Document(-1, text, ""));
    }

    /**
     * The best {@code k} documents for a free-text query.
     */
    public TopDocs search(String query, int k) {

        Weights w = weights;

        List<String> terms = analyze(query);

        if (terms.isEmpty() || k <= 0) {

            return TopDocs.empty();
        }

        Map<String, Integer> counts = new LinkedHashMap<>();

        terms.forEach(t -> counts.merge(t, 1, Integer::sum));

        List<WeightedTerm> clauses = new ArrayList<>();

        counts.forEach((term, count) -> {

            clauses.add(new WeightedTerm(term, BODY, count));

            clauses.add(new WeightedTerm(IndexFiles.TITLE_FIELD + term, FIELD, count * w.title()));

            clauses.add(new WeightedTerm(IndexFiles.URL_FIELD + term, FIELD, count * w.url()));

            clauses.add(new WeightedTerm(IndexFiles.ANCHOR_FIELD + term, FIELD, count * w.anchor()));
        });

        String joined = joinedWord(query);

        if (joined != null) {

            clauses.add(new WeightedTerm(IndexFiles.URL_FIELD + joined, FIELD, w.url() * counts.size()));
        }

        TopDocs candidates = searcher.search(clauses, Math.max(k, CANDIDATES));

        Set<String> queryTerms = new HashSet<>(terms);

        List<ScoredDoc> reranked = new ArrayList<>(candidates.hits().size());

        for (ScoredDoc candidate : candidates.hits()) {

            reranked.add(new ScoredDoc(candidate.docId(),
                    candidate.score() + signals(candidate.docId(), queryTerms, joined, w)));
        }

        reranked.sort(Comparator.comparingDouble(ScoredDoc::score).reversed().thenComparingInt(ScoredDoc::docId));

        return new TopDocs(reranked.subList(0, Math.min(k, reranked.size())), candidates.totalHits(), candidates.scored());
    }

    /**
     * "Stack Overflow" → the analyzed form of "stackoverflow", for 2-3 word
     * queries; otherwise {@code null}.
     */
    private String joinedWord(String query) {

        String[] words = query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");

        List<String> kept = new ArrayList<>();

        for (String word : words) {

            if (!word.isEmpty()) {

                kept.add(word);
            }
        }

        if (kept.size() < 2 || kept.size() > 3) {

            return null;
        }

        List<String> analyzed = analyze(String.join("", kept));

        return analyzed.size() == 1 ? analyzed.getFirst() : null;
    }

    private double signals(int docId, Set<String> queryTerms, String joined, Weights w) {

        StoredDocument doc = index.document(docId);

        double score = 0;

        double pageRank = index.pageRank(docId);

        score += w.pageRank() * Math.clamp(Math.log(Math.max(pageRank, 1e-6)), -3, 6);

        int rank = index.domainRank(docId);

        if (rank > 0) {

            // 1.0 for the most popular site, falling to 0 at rank 1,000,000.
            score += w.domain() * Math.max(0, 1 - Math.log10(rank) / 6.0);
        }

        Set<String> title = new HashSet<>(analyze(doc.title()));

        if (!title.isEmpty()) {

            long covered = queryTerms.stream().filter(title::contains).count();

            score += w.titleCoverage() * covered / queryTerms.size();

            if (title.equals(queryTerms)) {

                score += w.exactTitle();
            }
        }

        String path = doc.path();

        if (path.startsWith("http")) {

            Set<String> urlWords = new HashSet<>(analyze(DiskIndexWriter.urlWords(hostPart(path))));

            boolean namesSite = queryTerms.stream().anyMatch(urlWords::contains)
                    || (joined != null && urlWords.contains(joined));

            if (namesSite && isHomepage(path)) {

                score += w.homepage();
            }

            score -= w.depth() * Math.max(0, depth(path) - 1);
        }

        return score;
    }

    private static String hostPart(String url) {

        int start = url.indexOf("://") + 3;

        int slash = url.indexOf('/', start);

        return slash < 0 ? url : url.substring(0, slash + 1);
    }

    private static boolean isHomepage(String url) {

        int start = url.indexOf("://") + 3;

        int slash = url.indexOf('/', start);

        return slash < 0 || url.length() == slash + 1;
    }

    private static int depth(String url) {

        int start = url.indexOf("://") + 3;

        int slash = url.indexOf('/', start);

        if (slash < 0) {

            return 0;
        }

        String path = url.substring(slash);

        int query = path.indexOf('?');

        if (query >= 0) {

            path = path.substring(0, query);
        }

        int segments = 0;

        for (String part : path.split("/")) {

            if (!part.isEmpty()) {

                segments++;
            }
        }

        return segments;
    }
}
