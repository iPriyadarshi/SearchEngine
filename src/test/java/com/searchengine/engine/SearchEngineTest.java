package com.searchengine.engine;

import com.searchengine.api.Index;
import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LengthFilter;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.StopwordFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.result.SearchResult;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SearchEngineTest {

    private SearchEngine newEngine() {

        Parser parser = new DefaultParser(
                new RegexTokenizer(),
                List.of(new LowercaseFilter(), new StopwordFilter(), new LengthFilter(2)));

        Index index = new MemoryInvertedIndex();

        return new SearchEngine(parser, index);
    }

    @Test
    void shouldIndexAndSearch() {

        SearchEngine engine = newEngine();

        engine.index(List.of(
                new Document(0, "Search engines build an inverted index.", "d0"),
                new Document(1, "Java programming language tutorial.", "d1"),
                new Document(2, "Ranking algorithms score documents for a query.", "d2")));

        assertEquals(3, engine.getDocumentCount());

        SearchResult result = engine.search("inverted index", new TFIDFRanker(engine.getIndex()));

        assertFalse(result.getResults().isEmpty());

        assertEquals(0, result.getResults().getFirst().getDocId());
    }

    @Test
    void shouldNormalizeQueryLikeDocuments() {

        SearchEngine engine = newEngine();

        engine.index(List.of(new Document(0, "Powerful Search Engines", "d0")));

        // Upper-case query with a stopword should still match.
        List<String> terms = engine.analyzeQuery("THE SEARCH");

        assertTrue(terms.contains("search"));

        assertFalse(terms.contains("the"));
    }

    @Test
    void shouldSupportIncrementalIndexing() {

        SearchEngine engine = newEngine();

        engine.index(List.of(new Document(0, "first document about search", "d0")));

        engine.indexDocument(new Document(1, "second document about ranking", "d1"));

        assertEquals(2, engine.getDocumentCount());

        SearchResult result = engine.search("ranking", new TFIDFRanker(engine.getIndex()));

        assertEquals(1, result.getResults().getFirst().getDocId());
    }
}
