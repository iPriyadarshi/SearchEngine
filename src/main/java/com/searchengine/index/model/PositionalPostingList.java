package com.searchengine.index.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Positional posting list for a single term: maps each document to the list of
 * positions at which the term occurs. Also knows how to project itself onto a
 * plain {@link PostingList} so positional indexes stay compatible with rankers
 * that only need term frequencies.
 */
public class PositionalPostingList {

    private final Map<Integer, PositionalPosting> postings = new TreeMap<>();

    public void add(int docId, int position) {

        postings.computeIfAbsent(docId, PositionalPosting::new).addPosition(position);
    }

    public int documentFrequency() {

        return postings.size();
    }

    public List<Integer> getPositions(int docId) {

        PositionalPosting posting = postings.get(docId);

        return posting == null ? Collections.emptyList() : posting.getPositions();
    }

    public java.util.Collection<PositionalPosting> getPostings() {

        return postings.values();
    }

    /**
     * Build a frequency-only {@link PostingList} view for ranker compatibility.
     */
    public PostingList toPostingList() {

        PostingList list = new PostingList();

        for (PositionalPosting posting : postings.values()) {

            for (int i = 0; i < posting.getTermFrequency(); i++) {

                list.add(posting.getDocId());
            }
        }

        return list;
    }
}
