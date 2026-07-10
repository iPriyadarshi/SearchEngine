package com.searchengine.parser.filter;

import com.searchengine.api.TokenFilter;
import com.searchengine.parser.stemmer.PorterStemmer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reduces each token to its Porter stem so that morphological variants of a
 * word share a single index term. Should run after lowercasing.
 */
public class PorterStemmerFilter implements TokenFilter {

    private final PorterStemmer stemmer = new PorterStemmer();

    @Override
    public List<String> apply(List<String> tokens) {

        List<String> result = new ArrayList<>(tokens.size());

        for (String token : tokens) {

            result.add(stemmer.stem(token));
        }

        return result;
    }
}
