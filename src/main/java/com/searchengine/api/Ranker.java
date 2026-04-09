package com.searchengine.api;

import com.searchengine.query.result.RankedDocument;

import java.util.List;

public interface Ranker {

    List<RankedDocument> rank(List<String> queryTerms);
}