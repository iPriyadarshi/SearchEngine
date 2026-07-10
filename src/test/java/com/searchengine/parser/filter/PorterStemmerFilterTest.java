package com.searchengine.parser.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PorterStemmerFilterTest {

    @Test
    void shouldStemEveryToken() {

        PorterStemmerFilter filter = new PorterStemmerFilter();

        List<String> stemmed = filter.apply(List.of("connection", "connected", "connecting"));

        // All variants collapse to a single shared stem.
        assertEquals(List.of("connect", "connect", "connect"), stemmed);
    }
}
