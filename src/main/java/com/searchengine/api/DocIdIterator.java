package com.searchengine.api;

/**
 * Forward-only cursor over an ascending set of document ids. Postings lists
 * and boolean combinations of them (AND / OR / NOT) share this contract, so
 * they compose freely.
 * <p>
 * A fresh iterator is unpositioned ({@link #docId()} == -1); call
 * {@link #nextDoc()} or {@link #advance(int)} first.
 */
public interface DocIdIterator {

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
     * Upper bound on the number of documents this iterator can return, used
     * to order work (e.g. lead a conjunction with its rarest clause).
     */
    long cost();
}
