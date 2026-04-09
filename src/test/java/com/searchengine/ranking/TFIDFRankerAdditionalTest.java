package com.searchengine.ranking;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TFIDFRankerAdditionalTest {

    @Test
    void testHigherTermFrequencyScoresHigher() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();

        // Three docs so idf = log(3/2) > 0; d1 has "java" once, d2 has "java" three times
        Document d1 = new Document(1, "doc1", "path1");
        Document d2 = new Document(2, "doc2", "path2");
        Document d3 = new Document(3, "doc3", "path3");

        index.addDocument(d1, Arrays.asList("java", "other"));
        index.addDocument(d2, Arrays.asList("java", "java", "java", "other"));
        index.addDocument(d3, Arrays.asList("only", "other", "terms"));

        TFIDFRanker ranker = new TFIDFRanker(index);
        List<RankedDocument> results = ranker.rank(Arrays.asList("java"));

        assertEquals(2, results.size());
        assertEquals(2, results.get(0).getDocId());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void testMultipleMatchingTermsAccumulateScore() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "doc1", "path1");
        Document d2 = new Document(2, "doc2", "path2");

        // d1 matches both terms, d2 matches only one
        index.addDocument(d1, Arrays.asList("search", "engine", "java"));
        index.addDocument(d2, Arrays.asList("search", "python"));

        TFIDFRanker ranker = new TFIDFRanker(index);
        List<RankedDocument> results = ranker.rank(Arrays.asList("search", "engine"));

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).getDocId());
    }

    @Test
    void testEmptyIndexReturnsEmpty() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        TFIDFRanker ranker = new TFIDFRanker(index);

        List<RankedDocument> results = ranker.rank(Arrays.asList("search"));
        assertTrue(results.isEmpty());
    }

    @Test
    void testEmptyQueryReturnsEmpty() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document d1 = new Document(1, "doc1", "path1");
        index.addDocument(d1, Arrays.asList("hello", "world"));

        TFIDFRanker ranker = new TFIDFRanker(index);
        List<RankedDocument> results = ranker.rank(List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void testAllScoresArePositive() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document d1 = new Document(1, "doc1", "path1");
        Document d2 = new Document(2, "doc2", "path2");
        Document d3 = new Document(3, "doc3", "path3");
        // "search" appears in d1 and d2 but not d3, so idf = log(3/2) > 0
        index.addDocument(d1, Arrays.asList("search", "engine"));
        index.addDocument(d2, Arrays.asList("search", "system"));
        index.addDocument(d3, Arrays.asList("machine", "learning"));

        TFIDFRanker ranker = new TFIDFRanker(index);
        List<RankedDocument> results = ranker.rank(Arrays.asList("search"));

        assertFalse(results.isEmpty());
        for (RankedDocument rd : results) {
            assertTrue(rd.getScore() > 0.0, "Score should be positive");
        }
    }
}
