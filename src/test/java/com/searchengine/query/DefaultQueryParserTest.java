package com.searchengine.query;

import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.parser.DefaultQueryParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultQueryParserTest {

    private final DefaultQueryParser parser = new DefaultQueryParser();

    @Test
    void testSingleTermQuery() {
        Query query = parser.parse("search");
        assertInstanceOf(TermQuery.class, query);
        TermQuery tq = (TermQuery) query;
        assertEquals(List.of("search"), tq.getTerms());
    }

    @Test
    void testMultipleTermsQuery() {
        Query query = parser.parse("search engine java");
        assertInstanceOf(TermQuery.class, query);
        TermQuery tq = (TermQuery) query;
        assertEquals(List.of("search", "engine", "java"), tq.getTerms());
    }

    @Test
    void testTermsAreConvertedToLowercase() {
        Query query = parser.parse("Search ENGINE");
        assertInstanceOf(TermQuery.class, query);
        TermQuery tq = (TermQuery) query;
        assertEquals(List.of("search", "engine"), tq.getTerms());
    }

    @Test
    void testExtraWhitespaceBetweenTerms() {
        Query query = parser.parse("hello   world");
        assertInstanceOf(TermQuery.class, query);
        TermQuery tq = (TermQuery) query;
        assertEquals(List.of("hello", "world"), tq.getTerms());
    }
}
