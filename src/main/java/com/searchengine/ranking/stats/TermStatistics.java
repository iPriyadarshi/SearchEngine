package com.searchengine.ranking.stats;

public class TermStatistics {

    private final String term;
    private final int documentFrequency;

    public TermStatistics(String term, int documentFrequency) {
        this.term = term;
        this.documentFrequency = documentFrequency;
    }

    public String getTerm() {
        return term;
    }

    public int getDocumentFrequency() {
        return documentFrequency;
    }
}