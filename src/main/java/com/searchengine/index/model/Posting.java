package com.searchengine.index.model;

public class Posting {

    private final int docId;

    private int termFrequency;

    public Posting(int docId) {

        this.docId = docId;
        this.termFrequency = 1;
    }

    public int getDocId() {

        return docId;
    }

    public int getTermFrequency() {

        return termFrequency;
    }

    public void incrementFrequency() {

        termFrequency++;
    }
}