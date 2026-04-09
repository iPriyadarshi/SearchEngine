package com.searchengine.query.model;

import java.util.List;

public class TermQuery implements Query {

    private final List<String> terms;

    public TermQuery(List<String> terms) {
        this.terms = terms;
    }

    public List<String> getTerms() {
        return terms;
    }
}