package com.searchengine.query;

import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.parser.BooleanQueryParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BooleanQueryTest {

    @Test
    void testBooleanQueryParserThrowsUnsupportedOperationException() {
        BooleanQueryParser parser = new BooleanQueryParser();
        assertThrows(UnsupportedOperationException.class, () -> parser.parse("java AND search"));
    }

    @Test
    void testBooleanQueryExecutorThrowsUnsupportedOperationException() {
        BooleanQueryExecutor executor = new BooleanQueryExecutor();
        TermQuery t1 = new TermQuery(List.of("search"));
        BooleanQuery query = new BooleanQuery(List.of(t1), BooleanQuery.Operator.AND);
        assertThrows(UnsupportedOperationException.class, () -> executor.execute(query));
    }
}
