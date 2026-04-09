package com.searchengine.parser.core;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultParserAdditionalTest {

    @Test
    void testNoFiltersReturnAllTokens() {
        Parser parser = new DefaultParser(new RegexTokenizer(), Collections.emptyList());
        Document doc = new Document(0, "Hello World", "test");
        List<String> tokens = parser.parse(doc);
        assertEquals(List.of("Hello", "World"), tokens);
    }

    @Test
    void testEmptyDocumentReturnsEmptyList() {
        Parser parser = new DefaultParser(new RegexTokenizer(), List.of(new LowercaseFilter()));
        Document doc = new Document(0, "", "empty");
        List<String> tokens = parser.parse(doc);
        assertTrue(tokens.isEmpty());
    }

    @Test
    void testFiltersAreAppliedInOrder() {
        // Apply lowercase first, then stopword filter
        Parser parser = new DefaultParser(
                new RegexTokenizer(),
                List.of(new LowercaseFilter(), new StopwordFilter())
        );
        Document doc = new Document(0, "The Search IS Fast", "test");
        List<String> tokens = parser.parse(doc);
        // "the" and "is" are stopwords after lowercase
        assertEquals(List.of("search", "fast"), tokens);
    }

    @Test
    void testDocumentWithOnlyStopwordsReturnsEmpty() {
        Parser parser = new DefaultParser(
                new RegexTokenizer(),
                List.of(new LowercaseFilter(), new StopwordFilter())
        );
        Document doc = new Document(0, "this is a the", "test");
        List<String> tokens = parser.parse(doc);
        assertTrue(tokens.isEmpty());
    }
}
