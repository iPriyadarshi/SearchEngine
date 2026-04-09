package com.searchengine.query;

import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SearchResultTest {

    @Test
    void testGetResults() {
        RankedDocument rd = new RankedDocument(1, 0.9);
        SearchResult result = new SearchResult(List.of(rd));
        assertEquals(1, result.getResults().size());
        assertSame(rd, result.getResults().get(0));
    }

    @Test
    void testEmptyResults() {
        SearchResult result = new SearchResult(Collections.emptyList());
        assertTrue(result.getResults().isEmpty());
    }

    @Test
    void testMultipleResults() {
        List<RankedDocument> docs = List.of(
                new RankedDocument(1, 0.9),
                new RankedDocument(2, 0.7),
                new RankedDocument(3, 0.5)
        );
        SearchResult result = new SearchResult(docs);
        assertEquals(3, result.getResults().size());
    }
}
