package com.searchengine.api;

/**
 * Forward-only cursor over one term's postings, in increasing document-id
 * order. Unlike a materialized posting list it never holds the whole list in
 * memory, and {@link #advance(int)} can skip over documents without decoding
 * them, which is what makes conjunctions and top-k retrieval fast on large
 * indexes.
 * <p>
 * A fresh iterator is unpositioned ({@link #docId()} == -1); call
 * {@link #nextDoc()} or {@link #advance(int)} first.
 */
public interface PostingsIterator {

    int NO_MORE_DOCS = Integer.MAX_VALUE;

    /**
     * Current document id, -1 before the first call, or {@link #NO_MORE_DOCS}.
     */
    int docId();

    /**
     * Move to the next document and return its id, or {@link #NO_MORE_DOCS}.
     */
    int nextDoc();

    /**
     * Move to the first document whose id is {@code >= target} and return it,
     * or {@link #NO_MORE_DOCS}. Never moves backwards.
     */
    int advance(int target);

    /**
     * Term frequency in the current document.
     */
    int freq();

    /**
     * Ascending token positions of the term in the current document.
     */
    int[] positions();

    /**
     * Number of documents in the list (an upper bound on the work to exhaust it).
     */
    long cost();
}
