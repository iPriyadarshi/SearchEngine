package com.searchengine.query.executor;

import com.searchengine.api.Ranker;
import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;

import java.util.List;

public class RankedQueryExecutor {

    private final Ranker ranker;

    public RankedQueryExecutor(Ranker ranker) {
        this.ranker = ranker;
    }

    public SearchResult execute(Query query) {

        if (!(query instanceof TermQuery termQuery)) {

            throw new IllegalArgumentException("Only TermQuery supported currently");
        }

        List<String> terms = termQuery.getTerms();

        List<RankedDocument> rankedDocs = ranker.rank(terms);

        return new SearchResult(rankedDocs);
    }
}