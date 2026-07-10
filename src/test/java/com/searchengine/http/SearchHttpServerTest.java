package com.searchengine.http;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.engine.SearchEngine;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.ranking.core.BM25Ranker;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SearchHttpServerTest {

    private SearchHttpServer server;

    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {

        Parser parser = new DefaultParser(new RegexTokenizer(), List.of(new LowercaseFilter()));

        PositionalInvertedIndex index = new PositionalInvertedIndex();

        SearchEngine engine = new SearchEngine(parser, index);

        engine.index(List.of(
                new Document(0, "search engine inverted index", "d0"),
                new Document(1, "java ranking algorithms", "d1"),
                new Document(2, "search ranking of documents", "d2")));

        Map<String, com.searchengine.api.Ranker> rankers = new LinkedHashMap<>();

        rankers.put("tfidf", new TFIDFRanker(index));

        rankers.put("bm25", new BM25Ranker(index));

        server = new SearchHttpServer(0, engine, rankers,
                new PhraseQueryExecutor(index),
                new BooleanQueryExecutor(index, index, engine::analyzeQuery));

        server.start();

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {

        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + path))
                .GET()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEndpointReportsOk() throws Exception {

        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());

        assertTrue(response.body().contains("\"status\":\"ok\""));
    }

    @Test
    void searchEndpointReturnsRankedResults() throws Exception {

        HttpResponse<String> response = get("/search?q=search%20ranking&ranker=tfidf");

        assertEquals(200, response.statusCode());

        assertTrue(response.body().contains("\"mode\":\"tfidf\""));

        assertTrue(response.body().contains("\"docId\":2"));
    }

    @Test
    void unknownRankerReturns400() throws Exception {

        HttpResponse<String> response = get("/search?q=search&ranker=nope");

        assertEquals(400, response.statusCode());

        assertTrue(response.body().contains("error"));
    }

    @Test
    void phraseEndpointMatchesConsecutiveTerms() throws Exception {

        HttpResponse<String> response = get("/phrase?q=inverted%20index");

        assertEquals(200, response.statusCode());

        assertTrue(response.body().contains("\"docId\":0"));
    }

    @Test
    void booleanEndpointEvaluatesExpression() throws Exception {

        HttpResponse<String> response = get("/bool?q=search%20AND%20NOT%20engine");

        assertEquals(200, response.statusCode());

        // only d2 has "search" without "engine"
        assertTrue(response.body().contains("\"docId\":2"));

        assertTrue(!response.body().contains("\"docId\":0"));
    }
}
