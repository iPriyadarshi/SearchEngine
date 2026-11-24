package com.searchengine.api;

/**
 * Cursor over one term's postings, in increasing document-id order. Unlike a
 * materialized posting list it never holds the whole list in memory, and
 * {@link #advance(int)} can skip over documents without decoding them, which
 * is what makes conjunctions and top-k retrieval fast on large indexes.
 */
public interface PostingsIterator extends DocIdIterator {

    /**
     * Term frequency in the current document.
     */
    int freq();

    /**
     * Ascending token positions of the term in the current document.
     */
    int[] positions();
}
