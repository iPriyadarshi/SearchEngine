package com.searchengine.api;

/**
 * Supplies document length statistics used by length-normalizing rankers such
 * as BM25. Length is measured in number of indexed tokens.
 */
public interface DocumentLengthProvider {

    int getDocumentLength(int docId);

    double getAverageDocumentLength();
}
