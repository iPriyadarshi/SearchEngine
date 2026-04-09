package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LengthFilterAdditionalTest {

    @Test
    void testEmptyInputReturnsEmptyList() {
        TokenFilter filter = new LengthFilter(3);
        List<String> result = filter.apply(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    void testAllTokensTooShortReturnsEmpty() {
        TokenFilter filter = new LengthFilter(5);
        List<String> tokens = List.of("a", "bb", "ccc");
        List<String> result = filter.apply(tokens);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExactMinLengthIsKept() {
        TokenFilter filter = new LengthFilter(3);
        List<String> tokens = List.of("ab", "abc", "abcd");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("abc", "abcd"), result);
    }

    @Test
    void testMinLengthZeroKeepsAll() {
        TokenFilter filter = new LengthFilter(0);
        List<String> tokens = List.of("a", "", "bbb");
        List<String> result = filter.apply(tokens);
        assertEquals(tokens, result);
    }

    @Test
    void testOrderIsPreserved() {
        TokenFilter filter = new LengthFilter(2);
        List<String> tokens = List.of("zoo", "a", "bar", "b", "cat");
        List<String> result = filter.apply(tokens);
        assertEquals(List.of("zoo", "bar", "cat"), result);
    }
}
