package com.searchengine.parser.tokenizer;

import com.searchengine.api.Tokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RegexTokenizerAdditionalTest {

    private final Tokenizer tokenizer = new RegexTokenizer();

    @Test
    void testLeadingAndTrailingSpaces() {
        List<String> tokens = tokenizer.tokenize("  hello world  ");
        assertEquals(List.of("hello", "world"), tokens);
    }

    @Test
    void testOnlyPunctuationReturnsEmpty() {
        List<String> tokens = tokenizer.tokenize("!@#$%^&*()");
        assertTrue(tokens.isEmpty());
    }

    @Test
    void testMultipleSpacesBetweenWords() {
        List<String> tokens = tokenizer.tokenize("hello   world");
        assertEquals(List.of("hello", "world"), tokens);
    }

    @Test
    void testHyphenatedWordsSplitIntoTokens() {
        List<String> tokens = tokenizer.tokenize("well-known");
        assertEquals(List.of("well", "known"), tokens);
    }

    @Test
    void testSingleWord() {
        List<String> tokens = tokenizer.tokenize("search");
        assertEquals(List.of("search"), tokens);
    }

    @Test
    void testNewlineAndTabSeparators() {
        List<String> tokens = tokenizer.tokenize("hello\tworld\nfoo");
        assertEquals(List.of("hello", "world", "foo"), tokens);
    }
}
