package com.searchengine.index.core;

import com.searchengine.api.Index;
import com.searchengine.document.model.Document;
import com.searchengine.index.model.PostingList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MemoryInvertedIndex implements Index {

    private final Map<String, PostingList> index = new HashMap<>();

    private int totalDocuments = 0;

    @Override
    public void addDocument(Document doc, List<String> tokens) {

        totalDocuments++;

        for (String token : tokens) {

            PostingList postingList = index.computeIfAbsent(token, k -> new PostingList());

            postingList.add(doc.getId());
        }
    }

    @Override
    public PostingList getPostingList(String term) {

        return index.getOrDefault(term, new PostingList());
    }

    @Override
    public int getDocumentFrequency(String term) {

        return getPostingList(term).documentFrequency();
    }

    @Override
    public int getTotalDocuments() {

        return totalDocuments;
    }

    public Set<String> vocabulary() {

        return index.keySet();
    }
}