package com.searchengine.index;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.PositionalInvertedIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PositionalInvertedIndexTest {

    @Test
    void shouldRecordPositions() {

        PositionalInvertedIndex index = new PositionalInvertedIndex();

        index.addDocument(new Document(0, "d0", "d0"),
                List.of("search", "engine", "search", "index"));

        // "search" occurs at positions 0 and 2.
        assertEquals(List.of(0, 2), index.getPositions("search", 0));

        assertEquals(List.of(1), index.getPositions("engine", 0));
    }

    @Test
    void shouldBehaveAsPlainIndexForRankers() {

        PositionalInvertedIndex index = new PositionalInvertedIndex();

        index.addDocument(new Document(0, "d0", "d0"), List.of("search", "search", "engine"));
        index.addDocument(new Document(1, "d1", "d1"), List.of("engine"));

        assertEquals(2, index.getTotalDocuments());

        assertEquals(2, index.getDocumentFrequency("engine"));

        // term frequency projected through the frequency-only posting list view
        assertEquals(2, index.getPostingList("search").termFrequency(0));

        // (3 tokens + 1 token) / 2 docs = 2.0
        assertEquals(2.0, index.getAverageDocumentLength());
    }

    @Test
    void shouldReturnEmptyForUnknownTerm() {

        PositionalInvertedIndex index = new PositionalInvertedIndex();

        index.addDocument(new Document(0, "d0", "d0"), List.of("hello"));

        assertTrue(index.getPositions("missing", 0).isEmpty());

        assertEquals(0, index.getDocumentFrequency("missing"));
    }
}
