package com.searchengine.query.disk;

import com.searchengine.api.DocIdIterator;
import com.searchengine.api.PostingsIterator;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.TermInfo;
import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.disk.TopDocs.ScoredDoc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Evaluates boolean query trees (from {@code BooleanQueryParser}) against a
 * {@link DiskIndex} by composing {@link DocIdIterators}. Matches are returned
 * in ascending doc-id order with score 1.0, like the in-memory
 * {@code BooleanQueryExecutor}; all matches are counted but only the first
 * {@code limit} are kept. {@link #searchRanked} instead orders the matches by
 * relevance to the query's positive (non-negated) terms.
 */
public final class BooleanSearcher {

    // Worst first; among equal scores the larger doc id is worse.
    private static final Comparator<ScoredDoc> WORST_FIRST =
            Comparator.comparingDouble(ScoredDoc::score).thenComparing(ScoredDoc::docId, Comparator.reverseOrder());

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

    /**
     * Same matches as {@link #search}, best first: each matching document is
     * scored on the terms that are not under a NOT. A query with no positive
     * terms (e.g. {@code NOT x}) scores every match 0, leaving index order.
     */
    public TopDocs searchRanked(Query query, int k, Similarity similarity) {

        DocIdIterator matches = iterator(query);

        Map<String, Integer> counts = new LinkedHashMap<>();

        collectPositiveTerms(query, false, counts);

        List<PostingsIterator> postings = new ArrayList<>();

        List<Similarity.TermWeight> weights = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {

            TermInfo info = index.termInfo(entry.getKey());

            if (info != null) {

                postings.add(index.postings(info));

                weights.add(similarity.weight(info, index.numDocs(), index.averageDocLength(), entry.getValue()));
            }
        }

        PriorityQueue<ScoredDoc> heap = new PriorityQueue<>(Math.clamp(k, 1, 1024) + 1, WORST_FIRST);

        long total = 0;

        for (int doc = matches.nextDoc(); doc != DocIdIterator.NO_MORE_DOCS; doc = matches.nextDoc()) {

            total++;

            if (k <= 0) {

                continue;
            }

            double score = 0;

            int docLength = index.docLength(doc);

            for (int i = 0; i < postings.size(); i++) {

                PostingsIterator it = postings.get(i);

                if (it.docId() < doc) {

                    it.advance(doc);
                }

                if (it.docId() == doc) {

                    score += weights.get(i).score(it.freq(), docLength);
                }
            }

            heap.add(new ScoredDoc(doc, score));

            if (heap.size() > k) {

                heap.poll();
            }
        }

        List<ScoredDoc> hits = new ArrayList<>(heap);

        hits.sort(WORST_FIRST.reversed());

        return new TopDocs(hits, total, total);
    }

    private void collectPositiveTerms(Query query, boolean negated, Map<String, Integer> counts) {

        if (query instanceof TermQuery termQuery) {

            if (!negated) {

                for (String raw : termQuery.getTerms()) {

                    for (String term : analyzer.apply(raw)) {

                        counts.merge(term, 1, Integer::sum);
                    }
                }
            }

        } else if (query instanceof BooleanQuery booleanQuery) {

            boolean childNegated = negated || booleanQuery.getOperator() == BooleanQuery.Operator.NOT;

            for (Query clause : booleanQuery.getClauses()) {

                collectPositiveTerms(clause, childNegated, counts);
            }
        }
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
