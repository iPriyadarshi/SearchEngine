package com.searchengine.query.model;

import java.util.List;

public class BooleanQuery implements Query {

    public enum Operator {
        AND, OR, NOT
    }

    private final List<Query> clauses;
    private final Operator operator;

    public BooleanQuery(List<Query> clauses, Operator operator) {
        this.clauses = clauses;
        this.operator = operator;
    }

    public List<Query> getClauses() {
        return clauses;
    }

    public Operator getOperator() {
        return operator;
    }
}