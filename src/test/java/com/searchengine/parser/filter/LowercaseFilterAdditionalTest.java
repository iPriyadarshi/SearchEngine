package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LowercaseFilterAdditionalTest {

    private final TokenFilter filter = new LowercaseFilter();

    @Test
    void testEmptyInputReturnsEmptyList() {
        List<String> result = filter.apply(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    void testMixedCaseConversion() {
        List<String> tokens = List.of("HeLLo", "WoRLd");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("hello", "world"), result);
    }

    @Test
    void testNumbers() {
        List<String> tokens = List.of("Word123", "ABC2024");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("word123", "abc2024"), result);
    }

    @Test
    void testOrderIsPreserved() {
        List<String> tokens = List.of("C", "B", "A");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("c", "b", "a"), result);
    }
}
