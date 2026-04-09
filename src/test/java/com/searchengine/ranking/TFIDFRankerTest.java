package com.searchengine.ranking;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TFIDFRankerTest {

    @Test
    void shouldRankDocuments() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "doc1", "java code search engine");
        Document d2 = new Document(2, "doc2", "search engine implementation");
        Document d3 = new Document(3, "doc3", "machine learning");

        index.addDocument(d1, Arrays.asList("java", "code", "search", "engine"));
        index.addDocument(d2, Arrays.asList("search", "engine", "implementation"));
        index.addDocument(d3, Arrays.asList("machine", "learning"));

        TFIDFRanker ranker = new TFIDFRanker(index);

        List<RankedDocument> results = ranker.rank(Arrays.asList("search", "engine"));

        assertEquals(2, results.size());

        assertTrue(results.get(0).getScore() >= results.get(1).getScore());
    }

    @Test
    void shouldIgnoreUnknownTerms() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "doc1", "hello world");

        index.addDocument(d1, Arrays.asList("hello", "world"));

        TFIDFRanker ranker = new TFIDFRanker(index);

        List<RankedDocument> results = ranker.rank(Arrays.asList("nonexistent"));

        assertTrue(results.isEmpty());
    }
}