package com.searchengine.api;

import com.searchengine.query.model.Query;

/**
 * Converts raw user query string into a structured Query object.
 * <p>
 * Examples:
 * <p>
 * "search engine" -> TermQuery(["search","engine"])
 * <p>
 * "java AND search" -> BooleanQuery(...)
 * <p>
 * "\"search engine\"" -> PhraseQuery(...)
 *
 */
public interface QueryParser {

    Query parse(String queryString);

}