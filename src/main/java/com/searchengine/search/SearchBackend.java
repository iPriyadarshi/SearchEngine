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
     * Completions for a partly typed query, most popular first.
     */
    default List<String> complete(String prefix) {

        return List.of();
    }

    /**
     * @param total         number of matching documents, or -1 if not counted
     * @param suggestion    a spelling-corrected query to offer ("Did you mean"), or null
     * @param correctedFrom when the results are for the corrected query (in
     *                      {@code suggestion}) because the original found nothing:
     *                      the original query; otherwise null
     */
    record Hits(String mode, List<Hit> hits, long total, double tookMillis, String suggestion, String correctedFrom) {

        public Hits(String mode, List<Hit> hits, long total, double tookMillis) {

            this(mode, hits, total, tookMillis, null, null);
        }

        Hits withSuggestion(String suggestion) {

            return new Hits(mode, hits, total, tookMillis, suggestion, null);
        }

        Hits corrected(String original, String fixed) {

            return new Hits(mode, hits, total, tookMillis, fixed, original);
        }
    }
}
