package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StopwordFilterAdditionalTest {

    private final TokenFilter filter = new StopwordFilter();

    @Test
    void testEmptyInputReturnsEmptyList() {
        List<String> result = filter.apply(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    void testAllStopwordsRemovedReturnsEmpty() {
        List<String> tokens = List.of("the", "a", "an", "is", "are");
        List<String> result = filter.apply(tokens);
        assertTrue(result.isEmpty());
    }

    @Test
    void testStopwordsAreCaseSensitive() {
        // The filter only removes lowercase stopwords; uppercase should pass through
        List<String> tokens = List.of("The", "A", "IS");
        List<String> result = filter.apply(tokens);
        assertEquals(tokens, result);
    }

    @Test
    void testMultipleStopwordsInMixedList() {
        List<String> tokens = List.of("in", "search", "on", "engine", "for");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("search", "engine"), result);
    }

    @Test
    void testBeenAndBeingAreRemoved() {
        List<String> tokens = List.of("been", "being", "indexed");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("indexed"), result);
    }

    @Test
    void testOrderIsPreserved() {
        List<String> tokens = List.of("java", "is", "fast", "and", "reliable");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("java", "fast", "reliable"), result);
    }
}
