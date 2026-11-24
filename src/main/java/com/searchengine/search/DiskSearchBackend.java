package com.searchengine.search;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.StoredDocument;
import com.searchengine.query.disk.BooleanSearcher;
import com.searchengine.query.disk.PhraseSearcher;
import com.searchengine.query.disk.Similarity;
import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopKSearcher;
import com.searchengine.query.parser.BooleanQueryParser;
import com.searchengine.semantic.SemanticSearcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves queries from a memory-mapped {@link DiskIndex}. Thread-safe: every
 * query uses its own iterators. The default "bm25" ranking is the two-stage
 * {@link WebRanker} (body, title, URL and anchor fields, then link and site
 * signals), fused with semantic retrieval when the index has embeddings
 * ({@link HybridRanker}); "tfidf" and "cosine" score body text only.
 */
public class DiskSearchBackend implements SearchBackend {

    private final DiskIndex index;

    private final Parser parser;

    private final Path location;

    private final Map<String, Similarity> similarities = new LinkedHashMap<>();

    private final TopKSearcher topK;

    private final PhraseSearcher phrase;

    private final BooleanSearcher bool;

    private final WebRanker web;

    private final QueryAids aids;

    private final SemanticSearcher semantic;

    private final HybridRanker ranker;

    /**
     * Weight of semantic ranks in the fusion (see {@link HybridRanker}).
     */
    public static final double SEMANTIC_WEIGHT = 0.5;

    public DiskSearchBackend(DiskIndex index, Parser parser, Path location) {

        this.index = index;

        this.parser = parser;

        this.location = location;

        similarities.put("bm25", Similarity.bm25());

        similarities.put("tfidf", Similarity.tfIdf());

        similarities.put("cosine", Similarity.cosine());

        this.topK = new TopKSearcher(index);

        this.phrase = new PhraseSearcher(index);

        this.bool = new BooleanSearcher(index, this::analyze);

        this.web = new WebRanker(index, parser, WebRanker.Weights.defaults());

        QueryAids loaded;

        try {

            loaded = QueryAids.load(location);

        } catch (java.io.IOException e) {

            loaded = QueryAids.of(java.util.Map.of(), List.of());
        }

        this.aids = loaded;

        SemanticSearcher vectors = null;

        try {

            vectors = SemanticSearcher.open(location);

        } catch (java.io.IOException | RuntimeException e) {

            // Semantic search is optional; keyword search still works.
            System.err.println("Semantic search unavailable: " + e.getMessage());
        }

        this.semantic = vectors;

        this.ranker = new HybridRanker(web, semantic, SEMANTIC_WEIGHT);
    }

    /**
     * Whether this index has embeddings, so "bm25" searches are hybrid.
     */
    public boolean semanticEnabled() {

        return semantic != null;
    }

    @Override
    public List<String> complete(String prefix) {

        return aids.complete(prefix);
    }

    /**
     * Spelling, as on web search engines: when a word is unknown to the index
     * (a near-certain typo) or the query found nothing, answer the corrected
     * query and say so; otherwise just offer the correction.
     */
    private Hits withSpelling(String query, Hits hits, java.util.function.Function<String, Hits> rerun) {

        QueryAids.Correction correction = aids.correction(query);

        if (correction == null) {

            return hits;
        }

        String fixed = correction.text();

        if (correction.confident() || hits.hits().isEmpty()) {

            Hits corrected = rerun.apply(fixed);

            if (!corrected.hits().isEmpty()) {

                return corrected.corrected(query, fixed);
            }
        }

        return hits.withSuggestion(fixed);
    }

    /**
     * The two-stage ranker behind the default "bm25" ranking (exposed for tuning).
     */
    public WebRanker webRanker() {

        return web;
    }

    private List<String> analyze(String text) {

        return parser.parse(new Document(-1, text, "<query>"));
    }

    @Override
    public List<String> rankers() {

        return List.copyOf(similarities.keySet());
    }

    @Override
    public Hits search(String query, String ranker, int limit) {

        return search(query, ranker, limit, false);
    }

    @Override
    public Hits phrase(String query, int limit) {

        return phrase(query, limit, false);
    }

    @Override
    public Hits search(String query, String ranker, int limit, boolean exact) {

        Similarity similarity = similarities.get(ranker);

        if (similarity == null) {

            throw new IllegalArgumentException("unknown ranker: " + ranker);
        }

        long start = System.nanoTime();

        // BM25 is the default and uses field + link signals; the others stay plain body
        // scores, useful for comparison.
        TopDocs top = ranker.equals("bm25") ? this.ranker.search(query, limit) : topK.search(analyze(query), similarity, limit);

        Hits hits = toHits(ranker, query, top, start);

        if (exact) {

            return hits;
        }

        return withSpelling(query, hits, fixed -> {

            long again = System.nanoTime();

            TopDocs retry = ranker.equals("bm25") ? this.ranker.search(fixed, limit) : topK.search(analyze(fixed), similarity, limit);

            return toHits(ranker, fixed, retry, again);
        });
    }

    @Override
    public Hits phrase(String query, int limit, boolean exact) {

        long start = System.nanoTime();

        if (exact) {

            return toHits("phrase", query, phrase.search(analyze(query), limit), start);
        }

        return withSpelling(query, toHits("phrase", query, phrase.search(analyze(query), limit), start),
                fixed -> toHits("phrase", fixed, phrase.search(analyze(fixed), limit), System.nanoTime()));
    }

    @Override
    public Hits bool(String query, int limit) {

        long start = System.nanoTime();

        // Boolean queries filter; the remaining matches are ordered by BM25 relevance.
        TopDocs top = bool.searchRanked(new BooleanQueryParser().parse(query), limit, similarities.get("bm25"));

        return toHits("boolean", query, top, start);
    }

    private Hits toHits(String mode, String query, TopDocs top, long startNanos) {

        List<Hit> hits = new ArrayList<>(top.hits().size());

        for (TopDocs.ScoredDoc scored : top.hits()) {

            StoredDocument doc = index.document(scored.docId());

            hits.add(new Hit(scored.docId(), scored.score(), doc.title(), doc.path(),
                    Snippets.around(doc.text(), query)));
        }

        return new Hits(mode, hits, top.totalHits(), (System.nanoTime() - startNanos) / 1e6);
    }

    @Override
    public long documentCount() {

        return index.numDocs();
    }

    @Override
    public String describe() {

        return String.format("disk index %s (%,d documents, %,d terms)", location, index.numDocs(), index.numTerms());
    }

    @Override
    public void close() {

        if (semantic != null) {

            try {

                semantic.close();

            } catch (java.io.IOException ignored) {
                // Closing a read-only model; nothing to recover.
            }
        }

        index.close();
    }
}
