package com.searchengine.search;

import java.io.Closeable;
import java.util.List;

/**
 * What the CLI and HTTP server need from a search engine, independent of
 * whether the index lives in memory ({@link MemorySearchBackend}) or on disk
 * ({@link DiskSearchBackend}).
 */
public interface SearchBackend extends Closeable {

    /**
     * Ranker names accepted by {@link #search}; the first is the default.
     */
    List<String> rankers();

    default String defaultRanker() {

        return rankers().getFirst();
    }

    /**
     * Ranked keyword search.
     *
     * @throws IllegalArgumentException for an unknown ranker
     */
    Hits search(String query, String ranker, int limit);

    /**
     * Exact phrase search (terms adjacent and in order).
     */
    Hits phrase(String query, int limit);

    /**
     * Boolean search with AND / OR / NOT and parentheses.
     *
     * @throws IllegalArgumentException for a malformed expression
     */
    Hits bool(String query, int limit);

    long documentCount();

    /**
     * Human-readable description of the index, for startup messages.
     */
    String describe();

    @Override
    default void close() {
    }

    /**
     * One search result, ready for display.
     */
    record Hit(int docId, double score, String title, String path, String snippet) {
    }

    /**
     * @param total number of matching documents, or -1 if not counted
     */
    record Hits(String mode, List<Hit> hits, long total, double tookMillis) {
    }
}
