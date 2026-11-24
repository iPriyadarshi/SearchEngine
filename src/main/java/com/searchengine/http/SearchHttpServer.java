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
import java.util.List;
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
 * GET /search?q=...&amp;ranker=bm25|tfidf|cosine&amp;limit=N&amp;offset=M   ranked search
 * GET /phrase?q=...&amp;limit=N&amp;offset=M                            phrase search
 * GET /bool?q=... AND ...&amp;limit=N&amp;offset=M                      boolean search
 * GET /suggest?q=...                                    autocomplete (JSON array)
 * GET /health                                            liveness probe
 * </pre>
 *
 * The search endpoints return JSON; the root serves the single-page UI.
 * {@code offset} pages through results; like web search engines, only the
 * first {@value #MAX_WINDOW} results of a query can be paged to.
 * Requests are handled on a thread pool, so slow queries do not block others.
 */
public class SearchHttpServer {

    private static final int DEFAULT_LIMIT = 10;

    static final int MAX_LIMIT = 100;

    static final int MAX_WINDOW = 500;

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

        server.createContext("/suggest", guarded(this::handleSuggest));

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

    private void handleSuggest(HttpExchange exchange) throws IOException {

        String q = queryParams(exchange).getOrDefault("q", "");

        StringBuilder sb = new StringBuilder("{\"query\":\"").append(Json.escape(q)).append("\",\"suggestions\":[");

        // Bound the work a single keystroke can cause.
        List<String> completions = q.length() > 100 ? List.of() : backend.complete(q);

        for (int i = 0; i < completions.size(); i++) {

            sb.append(i > 0 ? "," : "").append('"').append(Json.escape(completions.get(i))).append('"');
        }

        respond(exchange, 200, sb.append("]}").toString());
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

        Window window = window(params);

        boolean exact = "1".equals(params.get("exact"));

        respond(exchange, 200, toJson(q, window.slice(backend.search(q, ranker, window.end(), exact)), window));
    }

    private void handlePhrase(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        Window window = window(params);

        boolean exact = "1".equals(params.get("exact"));

        respond(exchange, 200, toJson(q, window.slice(backend.phrase(q, window.end(), exact)), window));
    }

    private void handleBool(HttpExchange exchange) throws IOException {

        Map<String, String> params = queryParams(exchange);

        String q = params.getOrDefault("q", "");

        Window window = window(params);

        respond(exchange, 200, toJson(q, window.slice(backend.bool(q, window.end())), window));
    }

    /**
     * The requested page of results: hits [offset, offset + limit).
     */
    private record Window(int offset, int limit) {

        int end() {

            return offset + limit;
        }

        Hits slice(Hits all) {

            List<Hit> hits = all.hits();

            return new Hits(all.mode(), hits.subList(Math.min(offset, hits.size()), hits.size()),
                    all.total(), all.tookMillis(), all.suggestion(), all.correctedFrom());
        }
    }

    private Window window(Map<String, String> params) {

        int limit = Math.clamp(intParam(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);

        int offset = Math.clamp(intParam(params, "offset", 0), 0, MAX_WINDOW - limit);

        return new Window(offset, limit);
    }

    private static int intParam(Map<String, String> params, String name, int fallback) {

        try {

            return Integer.parseInt(params.getOrDefault(name, String.valueOf(fallback)));

        } catch (NumberFormatException e) {

            return fallback;
        }
    }

    private String toJson(String query, Hits result, Window window) {

        StringBuilder sb = new StringBuilder();

        sb.append("{\"query\":\"").append(Json.escape(query)).append("\",");

        sb.append("\"mode\":\"").append(Json.escape(result.mode())).append("\",");

        sb.append("\"count\":").append(result.hits().size()).append(",");

        sb.append("\"offset\":").append(window.offset()).append(",");

        if (result.total() >= 0) {

            sb.append("\"total\":").append(result.total()).append(",");
        }

        sb.append("\"tookMs\":").append(String.format(Locale.ROOT, "%.2f", result.tookMillis())).append(",");

        if (result.suggestion() != null) {

            sb.append("\"suggestion\":\"").append(Json.escape(result.suggestion())).append("\",");
        }

        if (result.correctedFrom() != null) {

            sb.append("\"correctedFrom\":\"").append(Json.escape(result.correctedFrom())).append("\",");
        }

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
