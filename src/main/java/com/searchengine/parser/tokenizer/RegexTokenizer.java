package com.searchengine.parser.tokenizer;

import com.searchengine.api.Tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RegexTokenizer implements Tokenizer {

    // Compiled once: String.split would recompile the pattern on every call.
    private static final Pattern SPLIT = Pattern.compile("[^a-zA-Z0-9]+");

    @Override
    public List<String> tokenize(String text) {

        String[] rawTokens = SPLIT.split(text);

        List<String> tokens = new ArrayList<>();

        for (String token : rawTokens) {

            if (!token.isEmpty()) {

                tokens.add(token);
            }
        }

        return tokens;
    }
}