package com.searchengine.parser.core;

import com.searchengine.api.Parser;
import com.searchengine.api.TokenFilter;
import com.searchengine.api.Tokenizer;
import com.searchengine.document.model.Document;
import com.searchengine.parser.filter.LengthFilter;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DefaultParserTest {

    @Test
    void testFullPipeline() {

        Tokenizer tokenizer = new RegexTokenizer();

        List<TokenFilter> filters = List.of(

                new LowercaseFilter(),

                new StopwordFilter(),

                new LengthFilter(2));

        Parser parser = new DefaultParser(tokenizer, filters);

        Document doc = new Document(0, "This is a Search Engine", "test");

        List<String> tokens = parser.parse(doc);

        assertEquals(List.of("search", "engine"), tokens);
    }
}