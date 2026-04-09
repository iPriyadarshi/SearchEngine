package com.searchengine.ranking.core;

import com.searchengine.api.Index;
import com.searchengine.api.Ranker;
import com.searchengine.query.result.RankedDocument;

import java.util.List;

public class BM25Ranker implements Ranker {

    private final Index index;

    public BM25Ranker(Index index) {
        this.index = index;
    }

    @Override
    public List<RankedDocument> rank(List<String> queryTerms) {

        throw new UnsupportedOperationException("BM25 will be implemented in later step");
    }
}