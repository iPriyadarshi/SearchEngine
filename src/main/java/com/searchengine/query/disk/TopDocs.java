package com.searchengine.query.disk;

import java.util.List;

/**
 * The best-scoring documents of a query, best first.
 *
 * @param totalHits number of matching documents, or -1 when the search
 *                  skipped non-competitive documents and so did not count them
 * @param scored    documents whose score was actually computed (a measure of work)
 */
public record TopDocs(List<ScoredDoc> hits, long totalHits, long scored) {

    public record ScoredDoc(int docId, double score) {
    }

    public static TopDocs empty() {

        return new TopDocs(List.of(), 0, 0);
    }
}
