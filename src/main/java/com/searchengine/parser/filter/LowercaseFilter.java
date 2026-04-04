package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;

import java.util.ArrayList;
import java.util.List;

public class LowercaseFilter implements TokenFilter {

    @Override
    public List<String> apply(List<String> tokens) {

        List<String> result = new ArrayList<>();

        for (String token : tokens) {

            result.add(token.toLowerCase());
        }

        return result;
    }
}