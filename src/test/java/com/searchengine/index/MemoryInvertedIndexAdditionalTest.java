package com.searchengine.index;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.index.model.PostingList;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MemoryInvertedIndexAdditionalTest {

    @Test
    void testInitialTotalDocumentsIsZero() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        assertEquals(0, index.getTotalDocuments());
    }

    @Test
    void testUnknownTermReturnsEmptyPostingList() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        PostingList postingList = index.getPostingList("unknown");
        assertNotNull(postingList);
        assertEquals(0, postingList.documentFrequency());
    }

    @Test
    void testUnknownTermDocumentFrequencyIsZero() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        assertEquals(0, index.getDocumentFrequency("missing"));
    }

    @Test
    void testVocabularyContainsIndexedTerms() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document doc = new Document(1, "search engine", "doc1");
        index.addDocument(doc, List.of("search", "engine"));

        Set<String> vocab = index.vocabulary();
        assertTrue(vocab.contains("search"));
        assertTrue(vocab.contains("engine"));
        assertEquals(2, vocab.size());
    }

    @Test
    void testTermFrequencyIsTracked() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document doc = new Document(1, "content", "doc1");
        index.addDocument(doc, List.of("java", "java", "search"));

        PostingList javaList = index.getPostingList("java");
        assertEquals(2, javaList.termFrequency(1));
    }

    @Test
    void testEmptyTokenListIncrementsTotalDocs() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        Document doc = new Document(1, "", "empty");
        index.addDocument(doc, Collections.emptyList());

        assertEquals(1, index.getTotalDocuments());
        assertEquals(0, index.vocabulary().size());
    }

    @Test
    void testTermAppearingInAllDocumentsHasCorrectDf() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        for (int i = 0; i < 5; i++) {
            Document doc = new Document(i, "doc" + i, "path" + i);
            index.addDocument(doc, List.of("common", "unique" + i));
        }

        assertEquals(5, index.getDocumentFrequency("common"));
        assertEquals(1, index.getDocumentFrequency("unique0"));
    }
}
