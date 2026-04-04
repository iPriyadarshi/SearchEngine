package com.searchengine.index.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostingList {

    private final Map<Integer, Posting> postings = new HashMap<>();

    public void add(int docId) {

        Posting posting = postings.get(docId);

        if (posting == null) {

            postings.put(docId, new Posting(docId));
        } else {

            posting.incrementFrequency();
        }
    }

    public List<Posting> getPostings() {

        return new ArrayList<>(postings.values());
    }

    public int documentFrequency() {

        return postings.size();
    }

    public int termFrequency(int docId) {

        Posting posting = postings.get(docId);

        if (posting == null) {

            return 0;
        }

        return posting.getTermFrequency();
    }
}