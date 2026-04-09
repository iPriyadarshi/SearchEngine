package com.searchengine.query.model;

import java.util.List;

public class PhraseQuery implements Query {

    private final List<String> terms;

    public PhraseQuery(List<String> terms) {
        this.terms = terms;
    }

    public List<String> getTerms() {
        return terms;
    }
}