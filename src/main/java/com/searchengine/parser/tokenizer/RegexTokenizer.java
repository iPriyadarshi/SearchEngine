package com.searchengine.parser.tokenizer;

import com.searchengine.api.Tokenizer;

import java.util.ArrayList;
import java.util.List;

public class RegexTokenizer implements Tokenizer {

    private static final String SPLIT_REGEX = "[^a-zA-Z0-9]+";

    @Override
    public List<String> tokenize(String text) {

        String[] rawTokens = text.split(SPLIT_REGEX);

        List<String> tokens = new ArrayList<>();

        for (String token : rawTokens) {

            if (!token.isEmpty()) {

                tokens.add(token);
            }
        }

        return tokens;
    }
}