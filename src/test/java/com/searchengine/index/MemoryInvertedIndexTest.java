package com.searchengine.index;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MemoryInvertedIndexTest {

    @Test
    void testIndexing() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document doc1 = new Document(1, "search engine", "doc1");

        Document doc2 = new Document(2, "search system", "doc2");

        index.addDocument(doc1, List.of("search", "engine"));

        index.addDocument(doc2, List.of("search", "system"));

        assertEquals(2, index.getDocumentFrequency("search"));

        assertEquals(1, index.getDocumentFrequency("engine"));

        assertEquals(2, index.getTotalDocuments());
    }
}