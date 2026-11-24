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
 * query uses its own iterators. BM25 is the default ranker, since its
 * length normalization matters on real-world corpora.
 */
public class DiskSearchBackend implements SearchBackend {

    private final DiskIndex index;

    private final Parser parser;

    private final Path location;

    private final Map<String, Similarity> similarities = new LinkedHashMap<>();

    private final TopKSearcher topK;

    private final PhraseSearcher phrase;

    private final BooleanSearcher bool;

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

        return toHits(ranker, query, topK.search(analyze(query), similarity, limit), start);
    }

    @Override
    public Hits phrase(String query, int limit) {

        long start = System.nanoTime();

        return toHits("phrase", query, phrase.search(analyze(query), limit), start);
    }

    @Override
    public Hits bool(String query, int limit) {

        long start = System.nanoTime();

        return toHits("boolean", query, bool.search(new BooleanQueryParser().parse(query), limit), start);
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
