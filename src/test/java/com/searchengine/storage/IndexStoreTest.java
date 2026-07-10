package com.searchengine.storage;

import com.searchengine.api.Index;
import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.engine.SearchEngine;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.result.SearchResult;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IndexStoreTest {

    private Parser parser() {

        return new DefaultParser(new RegexTokenizer(), List.of(new LowercaseFilter()));
    }

    private SearchEngine buildAndIndex(Index index) {

        SearchEngine engine = new SearchEngine(parser(), index);

        engine.index(List.of(
                new Document(0, "search engine inverted index", "d0"),
                new Document(1, "java ranking algorithms", "d1"),
                new Document(2, "search ranking documents", "d2")));

        return engine;
    }

    @Test
    void shouldRoundTripThroughDisk(@TempDir Path dir) throws IOException {

        SearchEngine original = buildAndIndex(new MemoryInvertedIndex());

        Path file = dir.resolve("index.se");

        IndexStore store = new IndexStore();

        store.save(original, file);

        assertTrue(java.nio.file.Files.exists(file));

        // Rebuild into a fresh engine and index.
        SearchEngine restored = new SearchEngine(parser(), new MemoryInvertedIndex());

        store.load(restored, file);

        assertEquals(original.getDocumentCount(), restored.getDocumentCount());

        SearchResult before = original.search("search ranking", new TFIDFRanker(original.getIndex()));

        SearchResult after = restored.search("search ranking", new TFIDFRanker(restored.getIndex()));

        assertEquals(
                before.getResults().stream().map(r -> r.getDocId()).toList(),
                after.getResults().stream().map(r -> r.getDocId()).toList());
    }

    @Test
    void shouldRebuildPositionsWhenLoadingIntoPositionalIndex(@TempDir Path dir) throws IOException {

        SearchEngine original = buildAndIndex(new PositionalInvertedIndex());

        Path file = dir.resolve("index.se");

        IndexStore store = new IndexStore();

        store.save(original, file);

        PositionalInvertedIndex restoredIndex = new PositionalInvertedIndex();

        store.load(new SearchEngine(parser(), restoredIndex), file);

        // token order preserved -> positions reconstructed
        assertEquals(List.of(0), restoredIndex.getPositions("search", 0));

        assertEquals(List.of(1), restoredIndex.getPositions("engine", 0));
    }
}
