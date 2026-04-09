package com.searchengine.ranking.stats;

import java.util.Map;

public class DocumentStatistics {

    private final int docId;
    private final Map<String, Integer> termFrequencies;
    private final int length;

    public DocumentStatistics(int docId, Map<String, Integer> termFrequencies, int length) {

        this.docId = docId;
        this.termFrequencies = termFrequencies;
        this.length = length;
    }

    public int getDocId() {
        return docId;
    }

    public Map<String, Integer> getTermFrequencies() {
        return termFrequencies;
    }

    public int getLength() {
        return length;
    }
}