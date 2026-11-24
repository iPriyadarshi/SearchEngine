package com.searchengine.http;

import com.searchengine.api.Ranker;
import com.searchengine.engine.SearchEngine;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.search.MemorySearchBackend;
import com.searchengine.search.SearchBackend;
import com.searchengine.search.SearchBackend.Hit;
import com.searchengine.search.SearchBackend.Hits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exposes a {@link SearchBackend} over HTTP using the JDK's built-in server
 * (no web framework dependency).
 *
 * <pre>
 * GET /                                                  responsive web UI
 * GET /search?q=...&amp;ranker=bm25|tfidf|cosine&amp;limit=N   ranked search
 * GET /phrase?q=...&amp;limit=N                             phrase search
 * GET /bool?q=... AND ...&amp;limit=N                       boolean search
 * GET /health                                            liveness probe
 * </pre>
 *
 * The search endpoints return JSON; the root serves the single-page UI.
 * Requests are handled on a thread pool, so slow queries do not block others.
 */
public class SearchHttpServer {

    private static final int DEFAULT_LIMIT = 10;

    static final int MAX_LIMIT = 100;

    private final HttpServer server;

    private final SearchBackend backend;

    private final ExecutorService executor;

    public SearchHttpServer(int port, SearchBackend backend) throws IOException {

        this.backend = backend;

        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/search", guarded(this::handleSearch));

        server.createContext("/phrase", guarded(this::handlePhrase));

        server.createContext("/bool", guarded(this::handleBool));

        server.createContext("/health", guarded(this::handleHealth));

        // Fallback context: serves the web UI at "/" and 404s everything else.
        server.createContext("/", guarded(this::handleRoot));

        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

        server.setExecutor(executor);
    }

    /**
     * Convenience constructor for the in-memory engine.
     */
    public SearchHttpServer(int port,
                            SearchEngine engine,
                            Map<String, Ranker> rankers,
                            PhraseQueryExecutor phraseExecutor,
                            BooleanQueryExecutor booleanExecutor) throws IOException {

        this(port, new MemorySearchBackend(engine, rankers, phraseExecutor, booleanExecutor, "documents"));
    }

    public void start() {

        server.start();
    }

    /**
     * Stop accepting requests, give in-flight ones up to {@code graceSeconds}
     * to finish, then release the worker threads.
     */
    public void stop(int graceSeconds) {

        server.stop(graceSeconds);

        executor.shutdownNow();
    }

    public void stop() {

        stop(0);
    }

    public int getPort() {

        return server.getAddress().getPort();
    }

    /**
     * Turns any unexpected failure into a JSON 500 instead of a dropped
     * connection.
     */
    private HttpHandler guarded(HttpHandler handler) {

        // Not try-with-resources: that would close the exchange before the
        // catch blocks get to send their error response.
        return exchange -> {

            try {

                handler.handle(exchange);

            } catch (IllegalArgumentException e) {

                respond(exchange, 400, error(e.getMessage()));

            } catch (RuntimeException e) {

                respond(exchange, 500, error("internal error"));

            } finally {

                exchange.close();
            }
        };
    }

    private void handleHealth(HttpExchange exchange) throws IOException {

        respond(exchange, 200, "{\"status\":\"ok\",\"documents\":" + backend.documentCount() + "}");
    }

    private void handleRoot(HttpExchange exchange) throws IOException {

        String path = exchange.getRequestURI().getPath();

        if (path.equals("/") || path.equals("/index.html") || path.equals("/ui")) {

            byte[] ui = readResource("/web/index.html");

            if (ui == null) {

                respond(exchange, 500, error("web UI resource not found"));

                return;
            }

            respondBytes(exchange, 200, "text/html; charset=utf-8", ui);

            return;
        }

        respond(exchange, 404, error("not found: " + path));
    }

    private byte[] readResource(String resource) throws IOException {

        try (InputStream in = getClass().getResourceAsStream(resource)) {

            return in == null ? null : in.readAllBytes();
        }
    }

    private void handleSearch(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        String ranker = params.getOrDefault("ranker", backend.defaultRanker()).toLowerCase(Locale.ROOT);

        respond(exchange, 200, toJson(q, backend.search(q, ranker, limit(params))));
    }

    private void handlePhrase(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        respond(exchange, 200, toJson(q, backend.phrase(q, limit(params))));
    }

    private void handleBool(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        respond(exchange, 200, toJson(q, backend.bool(q, limit(params))));
    }

    private int limit(Map<String, String> params) {

        try {

            int requested = Integer.parseInt(params.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT)));

            return Math.clamp(requested, 1, MAX_LIMIT);

        } catch (NumberFormatException e) {

            return DEFAULT_LIMIT;
        }
    }

    private String toJson(String query, Hits result) {

        StringBuilder sb = new StringBuilder();

        sb.append("{\"query\":\"").append(Json.escape(query)).append("\",");

        sb.append("\"mode\":\"").append(Json.escape(result.mode())).append("\",");

        sb.append("\"count\":").append(result.hits().size()).append(",");

        if (result.total() >= 0) {

            sb.append("\"total\":").append(result.total()).append(",");
        }

        sb.append("\"tookMs\":").append(String.format(Locale.ROOT, "%.2f", result.tookMillis())).append(",");

        sb.append("\"results\":[");

        for (int i = 0; i < result.hits().size(); i++) {

            Hit hit = result.hits().get(i);

            if (i > 0) {

                sb.append(",");
            }

            sb.append("{\"docId\":").append(hit.docId())
                    .append(",\"score\":").append(hit.score())
                    .append(",\"title\":\"").append(Json.escape(hit.title())).append("\"")
                    .append(",\"path\":\"").append(Json.escape(hit.path())).append("\"")
                    .append(",\"snippet\":\"").append(Json.escape(hit.snippet())).append("\"}");
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

        respondBytes(exchange, status, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private void respondBytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {

        exchange.getResponseHeaders().add("Content-Type", contentType);

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {

            os.write(bytes);
        }
    }
}
