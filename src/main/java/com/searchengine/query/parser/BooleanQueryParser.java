package com.searchengine.query.parser;

import com.searchengine.api.QueryParser;
import com.searchengine.query.model.BooleanQuery;
import com.searchengine.query.model.Query;
import com.searchengine.query.model.TermQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for boolean queries.
 * <p>
 * Grammar (precedence NOT &gt; AND &gt; OR):
 *
 * <pre>
 * expr   := orExpr
 * orExpr := andExpr ( OR andExpr )*
 * andExpr:= notExpr ( AND notExpr )*
 * notExpr:= NOT notExpr | atom
 * atom   := '(' expr ')' | TERM
 * </pre>
 *
 * Leaves are produced as single-term {@link TermQuery} instances. Term
 * normalization (lowercasing, stemming, ...) is deferred to the executor so the
 * parser stays independent of the analysis pipeline.
 */
public class BooleanQueryParser implements QueryParser {

    private List<String> tokens;

    private int pos;

    @Override
    public Query parse(String queryString) {

        this.tokens = tokenize(queryString);

        this.pos = 0;

        if (tokens.isEmpty()) {

            return new TermQuery(List.of());
        }

        Query query = orExpr();

        if (pos < tokens.size()) {

            throw new IllegalArgumentException("Unexpected token: " + tokens.get(pos));
        }

        return query;
    }

    private Query orExpr() {

        List<Query> clauses = new ArrayList<>();

        clauses.add(andExpr());

        while (isKeyword("OR")) {

            pos++;

            clauses.add(andExpr());
        }

        return clauses.size() == 1 ? clauses.getFirst() : new BooleanQuery(clauses, BooleanQuery.Operator.OR);
    }

    private Query andExpr() {

        List<Query> clauses = new ArrayList<>();

        clauses.add(notExpr());

        while (isKeyword("AND")) {

            pos++;

            clauses.add(notExpr());
        }

        return clauses.size() == 1 ? clauses.getFirst() : new BooleanQuery(clauses, BooleanQuery.Operator.AND);
    }

    private Query notExpr() {

        if (isKeyword("NOT")) {

            pos++;

            return new BooleanQuery(List.of(notExpr()), BooleanQuery.Operator.NOT);
        }

        return atom();
    }

    private Query atom() {

        if (pos >= tokens.size()) {

            throw new IllegalArgumentException("Unexpected end of query");
        }

        String token = tokens.get(pos);

        if (token.equals("(")) {

            pos++;

            Query inner = orExpr();

            expect(")");

            return inner;
        }

        if (token.equals(")")) {

            throw new IllegalArgumentException("Unexpected ')'");
        }

        pos++;

        return new TermQuery(List.of(token.toLowerCase()));
    }

    private void expect(String symbol) {

        if (pos >= tokens.size() || !tokens.get(pos).equals(symbol)) {

            throw new IllegalArgumentException("Expected '" + symbol + "'");
        }

        pos++;
    }

    private boolean isKeyword(String keyword) {

        return pos < tokens.size() && tokens.get(pos).equalsIgnoreCase(keyword);
    }

    private List<String> tokenize(String queryString) {

        List<String> result = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        for (char c : queryString.toCharArray()) {

            if (c == '(' || c == ')') {

                flush(current, result);

                result.add(String.valueOf(c));

            } else if (Character.isWhitespace(c)) {

                flush(current, result);

            } else {

                current.append(c);
            }
        }

        flush(current, result);

        return result;
    }

    private void flush(StringBuilder current, List<String> result) {

        if (!current.isEmpty()) {

            result.add(current.toString());

            current.setLength(0);
        }
    }
}
