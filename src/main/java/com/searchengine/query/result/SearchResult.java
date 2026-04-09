package com.searchengine.query.result;

import java.util.List;

public class SearchResult {

    private final List<RankedDocument> results;

    public SearchResult(List<RankedDocument> results) {
        this.results = results;
    }

    public List<RankedDocument> getResults() {
        return results;
    }
}