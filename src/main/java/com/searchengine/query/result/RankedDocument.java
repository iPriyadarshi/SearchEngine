package com.searchengine.query.result;

public class RankedDocument {

    private final int docId;
    private final double score;

    public RankedDocument(int docId, double score) {
        this.docId = docId;
        this.score = score;
    }

    public int getDocId() {
        return docId;
    }

    public double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "RankedDocument{" + "docId=" + docId + ", score=" + score + '}';
    }
}