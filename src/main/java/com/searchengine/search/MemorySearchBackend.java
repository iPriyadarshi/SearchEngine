package com.searchengine.search;

import com.searchengine.api.Ranker;
import com.searchengine.document.model.Document;
import com.searchengine.engine.ResultFormatter;
import com.searchengine.engine.SearchEngine;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.parser.BooleanQueryParser;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapts the in-memory {@link SearchEngine} and its executors to
 * {@link SearchBackend}.
 */
public class MemorySearchBackend implements SearchBackend {

    private final SearchEngine engine;

    private final Map<String, Ranker> rankers;

    private final PhraseQueryExecutor phraseExecutor;

    private final BooleanQueryExecutor booleanExecutor;

    private final String source;

    public MemorySearchBackend(SearchEngine engine,
                               Map<String, Ranker> rankers,
                               PhraseQueryExecutor phraseExecutor,
                               BooleanQueryExecutor booleanExecutor,
                               String source) {

        this.engine = engine;

        this.rankers = rankers;

        this.phraseExecutor = phraseExecutor;

        this.booleanExecutor = booleanExecutor;

        this.source = source;
    }

    public SearchEngine engine() {

        return engine;
    }

    @Override
    public List<String> rankers() {

        return List.copyOf(rankers.keySet());
    }

    @Override
    public Hits search(String query, String rankerName, int limit) {

        Ranker ranker = rankers.get(rankerName);

        if (ranker == null) {

            throw new IllegalArgumentException("unknown ranker: " + rankerName);
        }

        long start = System.nanoTime();

        return toHits(rankerName, engine.search(query, ranker), limit, start);
    }

    @Override
    public Hits phrase(String query, int limit) {

        long start = System.nanoTime();

        SearchResult result = phraseExecutor.execute(new PhraseQuery(engine.analyzeQuery(query)));

        return toHits("phrase", result, limit, start);
    }

    @Override
    public Hits bool(String query, int limit) {

        long start = System.nanoTime();

        return toHits("boolean", booleanExecutor.execute(new BooleanQueryParser().parse(query)), limit, start);
    }

    private Hits toHits(String mode, SearchResult result, int limit, long startNanos) {

        List<RankedDocument> docs = result.getResults();

        List<Hit> hits = new ArrayList<>();

        for (int i = 0; i < Math.min(limit, docs.size()); i++) {

            RankedDocument ranked = docs.get(i);

            Document doc = engine.getDocument(ranked.getDocId());

            hits.add(new Hit(ranked.getDocId(), ranked.getScore(),
                    doc != null ? doc.getTitle() : "",
                    doc != null ? doc.getPath() : "",
                    doc != null ? ResultFormatter.snippet(doc.getContent()) : ""));
        }

        return new Hits(mode, hits, docs.size(), (System.nanoTime() - startNanos) / 1e6);
    }

    @Override
    public long documentCount() {

        return engine.getDocumentCount();
    }

    @Override
    public String describe() {

        return String.format("in-memory index of %s (%,d documents)", source, engine.getDocumentCount());
    }
}
