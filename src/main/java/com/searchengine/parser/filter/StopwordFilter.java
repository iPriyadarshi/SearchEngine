package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class StopwordFilter implements TokenFilter {

    private static final Set<String> STOPWORDS = Set.of(

            "a", "an", "the", "is", "are", "was", "were",

            "in", "on", "at", "to", "for", "of",

            "and", "or", "not",

            "this", "that", "these", "those",

            "be", "been", "being");

    @Override
    public List<String> apply(List<String> tokens) {

        List<String> result = new ArrayList<>();

        for (String token : tokens) {

            if (!STOPWORDS.contains(token)) {

                result.add(token);
            }
        }

        return result;
    }
}