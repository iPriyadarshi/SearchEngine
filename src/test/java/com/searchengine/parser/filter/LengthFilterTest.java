package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LengthFilterTest {

    @Test
    void testMinimumLength2() {

        TokenFilter filter = new LengthFilter(2);

        List<String> tokens = List.of("a", "an", "search", "i");

        List<String> result = filter.apply(tokens);

        assertEquals(List.of("an", "search"), result);
    }

    @Test
    void testNoRemoval() {

        TokenFilter filter = new LengthFilter(1);

        List<String> tokens = List.of("a", "b");

        List<String> result = filter.apply(tokens);

        assertEquals(tokens, result);
    }
}