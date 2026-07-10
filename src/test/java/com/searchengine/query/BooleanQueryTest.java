package com.searchengine.query;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.model.Query;
import com.searchengine.query.parser.BooleanQueryParser;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BooleanQueryTest {

    private final BooleanQueryParser parser = new BooleanQueryParser();

    private MemoryInvertedIndex index() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        index.addDocument(new Document(0, "d0", "d0"), List.of("java", "search", "engine"));
        index.addDocument(new Document(1, "d1", "d1"), List.of("java", "web", "server"));
        index.addDocument(new Document(2, "d2", "d2"), List.of("python", "search", "index"));
        index.addDocument(new Document(3, "d3", "d3"), List.of("rust", "systems"));

        return index;
    }

    private BooleanQueryExecutor executor(MemoryInvertedIndex index) {

        // identity analyzer: terms in this corpus are already normalized
        return new BooleanQueryExecutor(index, index, term -> List.of(term.toLowerCase()));
    }

    private List<Integer> run(MemoryInvertedIndex index, String q) {

        Query query = parser.parse(q);

        SearchResult result = executor(index).execute(query);

        return result.getResults().stream().map(RankedDocument::getDocId).toList();
    }

    @Test
    void shouldEvaluateAnd() {

        MemoryInvertedIndex index = index();

        assertEquals(List.of(0), run(index, "java AND search"));
    }

    @Test
    void shouldEvaluateOr() {

        MemoryInvertedIndex index = index();

        assertEquals(List.of(0, 2), run(index, "engine OR python"));
    }

    @Test
    void shouldEvaluateNot() {

        MemoryInvertedIndex index = index();

        // java documents excluding those mentioning web
        assertEquals(List.of(0), run(index, "java AND NOT web"));
    }

    @Test
    void shouldRespectParenthesesAndPrecedence() {

        MemoryInvertedIndex index = index();

        // search in (java OR python) documents -> d0 and d2
        assertEquals(List.of(0, 2), run(index, "search AND (java OR python)"));
    }

    @Test
    void shouldEvaluateStandaloneNotAgainstUniverse() {

        MemoryInvertedIndex index = index();

        assertEquals(List.of(1, 2, 3), run(index, "NOT engine"));
    }
}
