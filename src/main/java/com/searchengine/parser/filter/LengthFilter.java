package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;

import java.util.ArrayList;
import java.util.List;

// Removes very short tokens.
public class LengthFilter implements TokenFilter {

    private final int minLength;

    public LengthFilter(int minLength) {

        this.minLength = minLength;
    }

    @Override
    public List<String> apply(List<String> tokens) {

        List<String> result = new ArrayList<>();

        for (String token : tokens) {

            if (token.length() >= minLength) {

                result.add(token);
            }
        }

        return result;
    }
}