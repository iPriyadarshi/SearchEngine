package com.searchengine.parser.core;

import com.searchengine.api.Parser;
import com.searchengine.api.TokenFilter;
import com.searchengine.api.Tokenizer;
import com.searchengine.document.model.Document;

import java.util.List;

// Pipeline orchestrator.
public class DefaultParser implements Parser {

    private final Tokenizer tokenizer;

    private final List<TokenFilter> filters;

    public DefaultParser(Tokenizer tokenizer,

                         List<TokenFilter> filters) {

        this.tokenizer = tokenizer;

        this.filters = filters;
    }

    @Override
    public List<String> parse(Document document) {

        List<String> tokens = tokenizer.tokenize(document.getContent());

        for (TokenFilter filter : filters) {

            tokens = filter.apply(tokens);
        }

        return tokens;
    }
}