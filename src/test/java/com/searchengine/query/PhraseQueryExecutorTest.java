package com.searchengine.query;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhraseQueryExecutorTest {

    private PositionalInvertedIndex index() {

        PositionalInvertedIndex index = new PositionalInvertedIndex();

        // "search engine" is a phrase here...
        index.addDocument(new Document(0, "d0", "d0"),
                List.of("a", "search", "engine", "indexes", "documents"));

        // ...but here the two words are not adjacent.
        index.addDocument(new Document(1, "d1", "d1"),
                List.of("search", "the", "web", "engine"));

        // phrase occurs twice
        index.addDocument(new Document(2, "d2", "d2"),
                List.of("search", "engine", "search", "engine"));

        return index;
    }

    @Test
    void shouldMatchOnlyConsecutiveTerms() {

        PhraseQueryExecutor executor = new PhraseQueryExecutor(index());

        SearchResult result = executor.execute(new PhraseQuery(List.of("search", "engine")));

        List<Integer> matchedDocs = result.getResults().stream()
                .map(RankedDocument::getDocId)
                .toList();

        assertTrue(matchedDocs.contains(0));

        assertTrue(matchedDocs.contains(2));

        // d1 has both terms but not adjacent -> no match.
        assertTrue(!matchedDocs.contains(1));
    }

    @Test
    void shouldScoreByNumberOfOccurrences() {

        PhraseQueryExecutor executor = new PhraseQueryExecutor(index());

        SearchResult result = executor.execute(new PhraseQuery(List.of("search", "engine")));

        // d2 contains the phrase twice, so it ranks first.
        assertEquals(2, result.getResults().getFirst().getDocId());

        assertEquals(2.0, result.getResults().getFirst().getScore());
    }

    @Test
    void shouldHandleSingleTermPhrase() {

        PhraseQueryExecutor executor = new PhraseQueryExecutor(index());

        SearchResult result = executor.execute(new PhraseQuery(List.of("documents")));

        assertEquals(1, result.getResults().size());

        assertEquals(0, result.getResults().getFirst().getDocId());
    }
}
