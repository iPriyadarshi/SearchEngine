package com.searchengine.ranking;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.ranking.core.BM25Ranker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BM25RankerTest {

    @Test
    void shouldRankByRelevance() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "d1", "d1");
        Document d2 = new Document(2, "d2", "d2");
        Document d3 = new Document(3, "d3", "d3");

        index.addDocument(d1, List.of("search", "engine", "search", "index"));
        index.addDocument(d2, List.of("search", "engine"));
        index.addDocument(d3, List.of("machine", "learning"));

        BM25Ranker ranker = new BM25Ranker(index);

        List<RankedDocument> results = ranker.rank(List.of("search"));

        assertEquals(2, results.size());

        // d1 mentions "search" twice, d2 once -> d1 should rank first.
        assertEquals(1, results.getFirst().getDocId());

        assertTrue(results.get(0).getScore() >= results.get(1).getScore());
    }

    @Test
    void shouldPenalizeLongDocumentsViaLengthNormalization() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        // Both docs contain "search" once, but d2 is much longer.
        index.addDocument(new Document(1, "short", "short"),
                List.of("search", "engine"));

        index.addDocument(new Document(2, "long", "long"),
                List.of("search", "a", "b", "c", "d", "e", "f", "g", "h"));

        BM25Ranker ranker = new BM25Ranker(index);

        List<RankedDocument> results = ranker.rank(List.of("search"));

        // The shorter document is more focused, so it should score higher.
        assertEquals(1, results.getFirst().getDocId());
    }

    @Test
    void shouldReturnEmptyForUnknownTerm() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        index.addDocument(new Document(1, "d", "d"), List.of("hello", "world"));

        BM25Ranker ranker = new BM25Ranker(index);

        assertTrue(ranker.rank(List.of("missing")).isEmpty());
    }
}
