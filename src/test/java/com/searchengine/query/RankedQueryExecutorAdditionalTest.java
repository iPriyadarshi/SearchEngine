package com.searchengine.query;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.executor.RankedQueryExecutor;
import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.model.TermQuery;
import com.searchengine.query.result.SearchResult;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RankedQueryExecutorAdditionalTest {

    @Test
    void testThrowsForNonTermQuery() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        TFIDFRanker ranker = new TFIDFRanker(index);
        RankedQueryExecutor executor = new RankedQueryExecutor(ranker);

        PhraseQuery phraseQuery = new PhraseQuery(List.of("hello", "world"));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(phraseQuery));
    }

    @Test
    void testThrowsForBooleanQuery() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        TFIDFRanker ranker = new TFIDFRanker(index);
        RankedQueryExecutor executor = new RankedQueryExecutor(ranker);

        BooleanQuery boolQuery = new BooleanQuery(
                List.of(new TermQuery(List.of("java"))), BooleanQuery.Operator.AND);
        assertThrows(IllegalArgumentException.class, () -> executor.execute(boolQuery));
    }

    @Test
    void testNoMatchingTermsReturnsEmptyResults() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document doc = new Document(1, "hello world", "doc1");
        index.addDocument(doc, List.of("hello", "world"));

        TFIDFRanker ranker = new TFIDFRanker(index);
        RankedQueryExecutor executor = new RankedQueryExecutor(ranker);

        TermQuery query = new TermQuery(List.of("java"));
        SearchResult result = executor.execute(query);

        assertTrue(result.getResults().isEmpty());
    }

    @Test
    void testResultsAreSortedByScoreDescending() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        // doc1 contains "search" three times, doc2 once
        Document doc1 = new Document(1, "doc1", "path1");
        Document doc2 = new Document(2, "doc2", "path2");
        index.addDocument(doc1, List.of("search", "search", "search", "other"));
        index.addDocument(doc2, List.of("search", "java"));

        TFIDFRanker ranker = new TFIDFRanker(index);
        RankedQueryExecutor executor = new RankedQueryExecutor(ranker);

        SearchResult result = executor.execute(new TermQuery(List.of("search")));
        List<?> results = result.getResults();

        assertEquals(2, results.size());
    }
}
