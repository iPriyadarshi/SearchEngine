package com.searchengine.ranking;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.ranking.core.CosineSimilarityRanker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CosineSimilarityRankerTest {

    @Test
    void testRanksDocumentsByRelevance() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "doc1", "search engine design");
        Document d2 = new Document(2, "doc2", "machine learning");
        Document d3 = new Document(3, "doc3", "search system implementation");

        index.addDocument(d1, Arrays.asList("search", "engine", "design"));
        index.addDocument(d2, Arrays.asList("machine", "learning"));
        index.addDocument(d3, Arrays.asList("search", "system", "implementation"));

        CosineSimilarityRanker ranker = new CosineSimilarityRanker(index);

        List<RankedDocument> results = ranker.rank(Arrays.asList("search", "engine"));

        assertEquals(2, results.size());
        // d1 has "engine" which d3 doesn't, so d1 should rank higher
        assertEquals(1, results.get(0).getDocId());
    }

    @Test
    void testIgnoresUnknownTerms() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document d1 = new Document(1, "doc1", "hello world");
        index.addDocument(d1, Arrays.asList("hello", "world"));

        CosineSimilarityRanker ranker = new CosineSimilarityRanker(index);
        List<RankedDocument> results = ranker.rank(Arrays.asList("nonexistent"));

        assertTrue(results.isEmpty());
    }

    @Test
    void testResultsAreSortedDescending() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "doc1", "path1");
        Document d2 = new Document(2, "doc2", "path2");

        index.addDocument(d1, Arrays.asList("java", "search", "engine", "fast"));
        index.addDocument(d2, Arrays.asList("search"));

        CosineSimilarityRanker ranker = new CosineSimilarityRanker(index);
        List<RankedDocument> results = ranker.rank(Arrays.asList("java", "search", "engine"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).getScore() >= results.get(1).getScore());
    }

    @Test
    void testEmptyQueryReturnsEmpty() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document d1 = new Document(1, "doc1", "hello");
        index.addDocument(d1, Arrays.asList("hello"));

        CosineSimilarityRanker ranker = new CosineSimilarityRanker(index);
        List<RankedDocument> results = ranker.rank(List.of());

        assertTrue(results.isEmpty());
    }

    @Test
    void testSingleDocumentSingleTerm() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document d1 = new Document(1, "java", "path1");
        index.addDocument(d1, Arrays.asList("java"));

        CosineSimilarityRanker ranker = new CosineSimilarityRanker(index);
        List<RankedDocument> results = ranker.rank(Arrays.asList("java"));

        assertEquals(1, results.size());
        assertEquals(1, results.get(0).getDocId());
    }
}
