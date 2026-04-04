package com.searchengine.index.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ForwardIndex {

    private final Map<Integer, Map<String, Integer>> index = new HashMap<>();

    public void addDocument(int docId, List<String> tokens) {

        Map<String, Integer> termFreq = index.computeIfAbsent(docId, k -> new HashMap<>());

        for (String token : tokens) {

            termFreq.merge(token, 1, Integer::sum);
        }
    }

    public Map<String, Integer> getTerms(int docId) {

        return index.getOrDefault(docId, Collections.emptyMap());
    }

    public int getTermFrequency(int docId, String term) {

        return getTerms(docId).getOrDefault(term, 0);
    }
}