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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves queries from a memory-mapped {@link DiskIndex}. Thread-safe: every
 * query uses its own iterators. The default "bm25" ranking is the two-stage
 * {@link WebRanker} (body, title, URL and anchor fields, then link and site
 * signals); "tfidf" and "cosine" score body text only.
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
    }

    @Override
    public List<String> complete(String prefix) {

        return aids.complete(prefix);
    }

    /**
     * Offer a spelling correction; when the query found nothing, answer the
     * corrected query instead.
     */
    private Hits withSpelling(String query, Hits hits, java.util.function.Function<String, Hits> rerun) {

        String fixed = aids.correct(query);

        if (fixed == null) {

            return hits;
        }

        if (hits.hits().isEmpty()) {

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

        Similarity similarity = similarities.get(ranker);

        if (similarity == null) {

            throw new IllegalArgumentException("unknown ranker: " + ranker);
        }

        long start = System.nanoTime();

        // BM25 is the default and uses field + link signals; the others stay plain body
        // scores, useful for comparison.
        TopDocs top = ranker.equals("bm25") ? web.search(query, limit) : topK.search(analyze(query), similarity, limit);

        return withSpelling(query, toHits(ranker, query, top, start), fixed -> {

            long again = System.nanoTime();

            TopDocs retry = ranker.equals("bm25") ? web.search(fixed, limit) : topK.search(analyze(fixed), similarity, limit);

            return toHits(ranker, fixed, retry, again);
        });
    }

    @Override
    public Hits phrase(String query, int limit) {

        long start = System.nanoTime();

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

        index.close();
    }
}
