package com.searchengine.query.disk;

import com.searchengine.api.DocIdIterator;
import com.searchengine.api.PostingsIterator;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.disk.TopDocs.ScoredDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Evaluates boolean query trees (from {@code BooleanQueryParser}) against a
 * {@link DiskIndex} by composing {@link DocIdIterators}. Matches are returned
 * in ascending doc-id order with score 1.0, like the in-memory
 * {@code BooleanQueryExecutor}; all matches are counted but only the first
 * {@code limit} are kept.
 */
public final class BooleanSearcher {

    private final DiskIndex index;

    private final Function<String, List<String>> analyzer;

    public BooleanSearcher(DiskIndex index, Function<String, List<String>> analyzer) {

        this.index = index;

        this.analyzer = analyzer;
    }

    public TopDocs search(Query query, int limit) {

        DocIdIterator matches = iterator(query);

        List<ScoredDoc> hits = new ArrayList<>();

        long total = 0;

        for (int doc = matches.nextDoc(); doc != DocIdIterator.NO_MORE_DOCS; doc = matches.nextDoc()) {

            if (hits.size() < limit) {

                hits.add(new ScoredDoc(doc, 1.0));
            }

            total++;
        }

        return new TopDocs(hits, total, total);
    }

    private DocIdIterator iterator(Query query) {

        if (query instanceof TermQuery termQuery) {

            return termIterator(termQuery);
        }

        if (query instanceof BooleanQuery booleanQuery) {

            List<DocIdIterator> children = new ArrayList<>();

            for (Query clause : booleanQuery.getClauses()) {

                children.add(iterator(clause));
            }

            return switch (booleanQuery.getOperator()) {

                case AND -> DocIdIterators.and(children);

                case OR -> DocIdIterators.or(children);

                case NOT -> DocIdIterators.not(DocIdIterators.or(children), index.numDocs());
            };
        }

        throw new IllegalArgumentException("Unsupported query type: " + query.getClass().getSimpleName());
    }

    /**
     * A leaf may analyze into several index terms, which must all be present.
     * A leaf that analyzes to nothing (e.g. a stopword) matches nothing.
     */
    private DocIdIterator termIterator(TermQuery query) {

        List<DocIdIterator> required = new ArrayList<>();

        for (String raw : query.getTerms()) {

            for (String term : analyzer.apply(raw)) {

                PostingsIterator postings = index.postings(term);

                if (postings == null) {

                    return DocIdIterators.empty();
                }

                required.add(postings);
            }
        }

        return required.isEmpty() ? DocIdIterators.empty() : DocIdIterators.and(required);
    }
}
