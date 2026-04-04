package com.searchengine.parser.tokenizer;

import com.searchengine.api.Tokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RegexTokenizerTest {

    private final Tokenizer tokenizer = new RegexTokenizer();

    @Test
    void testSimpleSentence() {

        String text = "Search engines are powerful";

        List<String> tokens = tokenizer.tokenize(text);

        assertEquals(List.of("Search", "engines", "are", "powerful"), tokens);
    }

    @Test
    void testRemovesPunctuation() {

        String text = "Hello, world!";

        List<String> tokens = tokenizer.tokenize(text);

        assertEquals(List.of("Hello", "world"), tokens);
    }

    @Test
    void testNumbers() {

        String text = "Version 2.0 released in 2024";

        List<String> tokens = tokenizer.tokenize(text);

        assertEquals(List.of("Version", "2", "0", "released", "in", "2024"), tokens);
    }

    @Test
    void testEmptyString() {

        List<String> tokens = tokenizer.tokenize("");

        assertTrue(tokens.isEmpty());
    }
}