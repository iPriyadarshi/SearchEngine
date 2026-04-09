package com.searchengine.query;

import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.model.TermQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class QueryModelTest {

    @Test
    void testTermQueryGetTerms() {
        TermQuery query = new TermQuery(List.of("search", "engine"));
        assertEquals(List.of("search", "engine"), query.getTerms());
    }

    @Test
    void testTermQueryEmptyTerms() {
        TermQuery query = new TermQuery(List.of());
        assertTrue(query.getTerms().isEmpty());
    }

    @Test
    void testPhraseQueryGetTerms() {
        PhraseQuery query = new PhraseQuery(List.of("information", "retrieval"));
        assertEquals(List.of("information", "retrieval"), query.getTerms());
    }

    @Test
    void testPhraseQuerySingleTerm() {
        PhraseQuery query = new PhraseQuery(List.of("java"));
        assertEquals(1, query.getTerms().size());
        assertEquals("java", query.getTerms().get(0));
    }

    @Test
    void testBooleanQueryGetOperatorAnd() {
        TermQuery t1 = new TermQuery(List.of("search"));
        TermQuery t2 = new TermQuery(List.of("engine"));
        BooleanQuery query = new BooleanQuery(List.of(t1, t2), BooleanQuery.Operator.AND);
        assertEquals(BooleanQuery.Operator.AND, query.getOperator());
    }

    @Test
    void testBooleanQueryGetOperatorOr() {
        TermQuery t1 = new TermQuery(List.of("java"));
        TermQuery t2 = new TermQuery(List.of("python"));
        BooleanQuery query = new BooleanQuery(List.of(t1, t2), BooleanQuery.Operator.OR);
        assertEquals(BooleanQuery.Operator.OR, query.getOperator());
    }

    @Test
    void testBooleanQueryGetOperatorNot() {
        TermQuery t1 = new TermQuery(List.of("spam"));
        BooleanQuery query = new BooleanQuery(List.of(t1), BooleanQuery.Operator.NOT);
        assertEquals(BooleanQuery.Operator.NOT, query.getOperator());
    }

    @Test
    void testBooleanQueryGetClauses() {
        TermQuery t1 = new TermQuery(List.of("a"));
        TermQuery t2 = new TermQuery(List.of("b"));
        BooleanQuery query = new BooleanQuery(List.of(t1, t2), BooleanQuery.Operator.AND);
        assertEquals(2, query.getClauses().size());
        assertSame(t1, query.getClauses().get(0));
        assertSame(t2, query.getClauses().get(1));
    }
}
