package com.searchengine.query;

import com.searchengine.document.model.Document;
import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.query.executor.RankedQueryExecutor;
import com.searchengine.query.model.Query;
import com.searchengine.query.parser.DefaultQueryParser;
import com.searchengine.query.result.SearchResult;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RankedQueryExecutorTest {

    @Test
    void shouldExecuteQuery() {

        MemoryInvertedIndex index = new MemoryInvertedIndex();

        Document d1 = new Document(1, "doc1", "search engine design");

        Document d2 = new Document(2, "doc2", "java implementation");

        index.addDocument(d1, java.util.List.of("search", "engine", "design"));

        index.addDocument(d2, java.util.List.of("java", "implementation"));

        TFIDFRanker ranker = new TFIDFRanker(index);

        RankedQueryExecutor executor = new RankedQueryExecutor(ranker);

        DefaultQueryParser parser = new DefaultQueryParser();

        Query query = parser.parse("search engine");

        SearchResult result = executor.execute(query);

        assertEquals(1, result.getResults().size());

        assertEquals(1, result.getResults().get(0).getDocId());
    }
}