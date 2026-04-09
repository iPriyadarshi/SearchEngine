package com.searchengine.query.parser;

import com.searchengine.api.QueryParser;
import com.searchengine.query.model.Query;

public class BooleanQueryParser implements QueryParser {

    @Override
    public Query parse(String queryString) {

        throw new UnsupportedOperationException(
                "Boolean parsing implemented later"
        );
    }
}