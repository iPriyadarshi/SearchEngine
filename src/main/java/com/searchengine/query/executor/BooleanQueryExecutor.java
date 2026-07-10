package com.searchengine.query.executor;

import com.searchengine.api.DocumentIdProvider;
import com.searchengine.api.Index;
import com.searchengine.index.model.Posting;
import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Evaluates a boolean query tree to a set of matching documents.
 * <p>
 * Boolean retrieval is set based rather than ranked: a document either matches
 * or it does not. Results are returned in ascending document-id order with a
 * uniform score of 1.0.
 * <p>
 * Leaf terms are normalized through the supplied analyzer so they match the
 * (possibly stemmed) terms stored in the index.
 */
public class BooleanQueryExecutor {

    private final Index index;

    private final DocumentIdProvider ids;

    private final Function<String, List<String>> analyzer;

    public BooleanQueryExecutor(Index index,
                                DocumentIdProvider ids,
                                Function<String, List<String>> analyzer) {

        this.index = index;

        this.ids = ids;

        this.analyzer = analyzer;
    }

    public SearchResult execute(Query query) {

        Set<Integer> matches = evaluate(query);

        List<RankedDocument> results = new ArrayList<>();

        for (int docId : new TreeSet<>(matches)) {

            results.add(new RankedDocument(docId, 1.0));
        }

        return new SearchResult(results);
    }

    private Set<Integer> evaluate(Query query) {

        if (query instanceof TermQuery termQuery) {

            return evaluateTerm(termQuery);
        }

        if (query instanceof BooleanQuery booleanQuery) {

            return evaluateBoolean(booleanQuery);
        }

        throw new IllegalArgumentException(
                "Unsupported query type: " + query.getClass().getSimpleName());
    }

    private Set<Integer> evaluateTerm(TermQuery termQuery) {

        // A leaf holds one raw term; analysis may expand it into several index
        // terms (rare) which must all be present -> intersection.
        Set<Integer> docs = null;

        for (String raw : termQuery.getTerms()) {

            for (String analyzed : analyzer.apply(raw)) {

                Set<Integer> forToken = postingDocs(analyzed);

                docs = docs == null ? forToken : intersect(docs, forToken);
            }
        }

        return docs == null ? new HashSet<>() : docs;
    }

    private Set<Integer> evaluateBoolean(BooleanQuery query) {

        List<Query> clauses = query.getClauses();

        return switch (query.getOperator()) {

            case AND -> {

                Set<Integer> result = null;

                for (Query clause : clauses) {

                    Set<Integer> docs = evaluate(clause);

                    result = result == null ? docs : intersect(result, docs);
                }

                yield result == null ? new HashSet<>() : result;
            }

            case OR -> {

                Set<Integer> result = new HashSet<>();

                for (Query clause : clauses) {

                    result.addAll(evaluate(clause));
                }

                yield result;
            }

            case NOT -> {

                Set<Integer> universe = ids.documentIds();

                for (Query clause : clauses) {

                    universe.removeAll(evaluate(clause));
                }

                yield universe;
            }
        };
    }

    private Set<Integer> postingDocs(String term) {

        Set<Integer> docs = new HashSet<>();

        for (Posting posting : index.getPostingList(term).getPostings()) {

            docs.add(posting.getDocId());
        }

        return docs;
    }

    private Set<Integer> intersect(Set<Integer> a, Set<Integer> b) {

        Set<Integer> result = new HashSet<>(a);

        result.retainAll(b);

        return result;
    }
}
