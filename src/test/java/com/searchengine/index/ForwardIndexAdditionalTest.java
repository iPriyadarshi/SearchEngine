package com.searchengine.index;

import com.searchengine.index.core.ForwardIndex;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ForwardIndexAdditionalTest {

    @Test
    void testMissingDocReturnsEmptyMap() {
        ForwardIndex index = new ForwardIndex();
        Map<String, Integer> terms = index.getTerms(999);
        assertTrue(terms.isEmpty());
    }

    @Test
    void testMissingTermReturnsZeroFrequency() {
        ForwardIndex index = new ForwardIndex();
        index.addDocument(1, List.of("hello"));
        assertEquals(0, index.getTermFrequency(1, "world"));
    }

    @Test
    void testMissingDocAndTermReturnsZero() {
        ForwardIndex index = new ForwardIndex();
        assertEquals(0, index.getTermFrequency(42, "nonexistent"));
    }

    @Test
    void testEmptyTokenListStillCreatesEntry() {
        ForwardIndex index = new ForwardIndex();
        index.addDocument(1, Collections.emptyList());
        assertTrue(index.getTerms(1).isEmpty());
    }

    @Test
    void testMultipleDocumentsAreIndependent() {
        ForwardIndex index = new ForwardIndex();
        index.addDocument(1, List.of("java", "search"));
        index.addDocument(2, List.of("python", "search", "search"));

        assertEquals(1, index.getTermFrequency(1, "java"));
        assertEquals(0, index.getTermFrequency(1, "python"));
        assertEquals(2, index.getTermFrequency(2, "search"));
        assertEquals(0, index.getTermFrequency(2, "java"));
    }

    @Test
    void testGetTermsReturnsAllTokens() {
        ForwardIndex index = new ForwardIndex();
        index.addDocument(5, List.of("a", "b", "a", "c"));

        Map<String, Integer> terms = index.getTerms(5);
        assertEquals(3, terms.size());
        assertEquals(2, terms.get("a"));
        assertEquals(1, terms.get("b"));
        assertEquals(1, terms.get("c"));
    }

    @Test
    void testAddingTokensToExistingDocument() {
        ForwardIndex index = new ForwardIndex();
        index.addDocument(1, List.of("hello"));
        index.addDocument(1, List.of("world", "hello"));

        assertEquals(2, index.getTermFrequency(1, "hello"));
        assertEquals(1, index.getTermFrequency(1, "world"));
    }
}
