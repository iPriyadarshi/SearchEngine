package com.searchengine.query.parser;

import com.searchengine.api.QueryParser;
import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;

import java.util.Arrays;
import java.util.List;

public class DefaultQueryParser implements QueryParser {

    @Override
    public Query parse(String queryString) {

        List<String> terms = Arrays.stream(queryString.split("\\s+")).map(String::toLowerCase).toList();

        return new TermQuery(terms);
    }
}