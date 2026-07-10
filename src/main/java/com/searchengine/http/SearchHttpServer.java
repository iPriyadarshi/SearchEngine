package com.searchengine.http;

import com.searchengine.api.Ranker;
import com.searchengine.document.model.Document;
import com.searchengine.engine.ResultFormatter;
import com.searchengine.engine.SearchEngine;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.parser.BooleanQueryParser;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the search engine over HTTP using the JDK's built-in server (no web
 * framework dependency).
 *
 * <pre>
 * GET /search?q=...&amp;ranker=tfidf|cosine|bm25&amp;limit=N   ranked search
 * GET /phrase?q=...&amp;limit=N                             phrase search
 * GET /bool?q=... AND ...                                boolean search
 * GET /health                                            liveness probe
 * </pre>
 *
 * All endpoints return JSON.
 */
public class SearchHttpServer {

    private static final int DEFAULT_LIMIT = 10;

    private final HttpServer server;

    private final SearchEngine engine;

    private final Map<String, Ranker> rankers;

    private final PhraseQueryExecutor phraseExecutor;

    private final BooleanQueryExecutor booleanExecutor;

    public SearchHttpServer(int port,
                            SearchEngine engine,
                            Map<String, Ranker> rankers,
                            PhraseQueryExecutor phraseExecutor,
                            BooleanQueryExecutor booleanExecutor) throws IOException {

        this.engine = engine;

        this.rankers = rankers;

        this.phraseExecutor = phraseExecutor;

        this.booleanExecutor = booleanExecutor;

        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/search", this::handleSearch);

        server.createContext("/phrase", this::handlePhrase);

        server.createContext("/bool", this::handleBool);

        server.createContext("/health", this::handleHealth);

        server.setExecutor(null);
    }

    public void start() {

        server.start();
    }

    public void stop() {

        server.stop(0);
    }

    public int getPort() {

        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {

        respond(exchange, 200, "{\"status\":\"ok\",\"documents\":" + engine.getDocumentCount() + "}");
    }

    private void handleSearch(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        String rankerName = params.getOrDefault("ranker", "tfidf");

        Ranker ranker = rankers.get(rankerName);

        if (ranker == null) {

            respond(exchange, 400, error("unknown ranker: " + rankerName));

            return;
        }

        SearchResult result = engine.search(q, ranker);

        respond(exchange, 200, toJson(q, rankerName, result, limit(params)));
    }

    private void handlePhrase(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        List<String> terms = engine.analyzeQuery(q);

        SearchResult result = phraseExecutor.execute(new PhraseQuery(terms));

        respond(exchange, 200, toJson(q, "phrase", result, limit(params)));
    }

    private void handleBool(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        try {

            SearchResult result = booleanExecutor.execute(new BooleanQueryParser().parse(q));

            respond(exchange, 200, toJson(q, "boolean", result, limit(params)));

        } catch (IllegalArgumentException e) {

            respond(exchange, 400, error(e.getMessage()));
        }
    }

    private int limit(Map<String, String> params) {

        try {

            return Math.max(1, Integer.parseInt(params.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT))));

        } catch (NumberFormatException e) {

            return DEFAULT_LIMIT;
        }
    }

    private String toJson(String query, String mode, SearchResult result, int limit) {

        List<RankedDocument> docs = result.getResults();

        int count = Math.min(limit, docs.size());

        StringBuilder sb = new StringBuilder();

        sb.append("{\"query\":\"").append(Json.escape(query)).append("\",");

        sb.append("\"mode\":\"").append(Json.escape(mode)).append("\",");

        sb.append("\"count\":").append(count).append(",");

        sb.append("\"results\":[");

        for (int i = 0; i < count; i++) {

            RankedDocument doc = docs.get(i);

            Document source = engine.getDocument(doc.getDocId());

            String path = source != null ? source.getPath() : "";

            String snippet = source != null ? ResultFormatter.snippet(source.getContent()) : "";

            if (i > 0) {

                sb.append(",");
            }

            sb.append("{\"docId\":").append(doc.getDocId())
                    .append(",\"score\":").append(doc.getScore())
                    .append(",\"path\":\"").append(Json.escape(path)).append("\"")
                    .append(",\"snippet\":\"").append(Json.escape(snippet)).append("\"}");
        }

        sb.append("]}");

        return sb.toString();
    }

    private String error(String message) {

        return "{\"error\":\"" + Json.escape(message) + "\"}";
    }

    private Map<String, String> queryParams(HttpExchange exchange) {

        Map<String, String> params = new HashMap<>();

        String raw = exchange.getRequestURI().getRawQuery();

        if (raw == null || raw.isEmpty()) {

            return params;
        }

        for (String pair : raw.split("&")) {

            int eq = pair.indexOf('=');

            if (eq < 0) {

                params.put(decode(pair), "");

            } else {

                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }

        return params;
    }

    private String decode(String value) {

        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {

            os.write(bytes);
        }
    }
}
