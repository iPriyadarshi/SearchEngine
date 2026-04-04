package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LowercaseFilterTest {

    private final TokenFilter filter = new LowercaseFilter();

    @Test
    void testLowercaseConversion() {

        List<String> tokens = List.of("Search", "ENGINE", "Java");

        List<String> result = filter.apply(tokens);

        assertEquals(List.of("search", "engine", "java"), result);
    }

    @Test
    void testAlreadyLowercase() {

        List<String> tokens = List.of("search");

        List<String> result = filter.apply(tokens);

        assertEquals(tokens, result);
    }
}