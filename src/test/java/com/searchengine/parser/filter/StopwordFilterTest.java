package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StopwordFilterTest {

    private final TokenFilter filter = new StopwordFilter();

    @Test
    void testRemovesStopwords() {

        List<String> tokens = List.of("this", "is", "a", "search", "engine");

        List<String> result = filter.apply(tokens);

        assertEquals(List.of("search", "engine"), result);
    }

    @Test
    void testNoStopwords() {

        List<String> tokens = List.of("information", "retrieval");

        List<String> result = filter.apply(tokens);

        assertEquals(tokens, result);
    }
}