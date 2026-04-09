package com.searchengine.ranking.core;

import com.searchengine.api.Index;
import com.searchengine.api.Ranker;
import com.searchengine.index.model.Posting;
import com.searchengine.index.model.PostingList;
import com.searchengine.query.result.RankedDocument;

import java.util.*;

public class CosineSimilarityRanker implements Ranker {

    private final Index index;

    public CosineSimilarityRanker(Index index) {
        this.index = index;
    }

    @Override
    public List<RankedDocument> rank(List<String> queryTerms) {

        Map<Integer, Double> scores = new HashMap<>();

        int N = index.getTotalDocuments();

        for (String term : queryTerms) {

            PostingList postingList = index.getPostingList(term);

            if (postingList == null) continue;

            int df = postingList.documentFrequency();

            double idf = Math.log((double) N / df);

            for (Posting posting : postingList.getPostings()) {

                int docId = posting.getDocId();
                int tf = posting.getTermFrequency();

                double weight = tf * idf;

                scores.merge(docId, weight, Double::sum);
            }
        }

        List<RankedDocument> results = new ArrayList<>();

        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            results.add(new RankedDocument(e.getKey(), e.getValue()));
        }

        results.sort(Comparator.comparingDouble(RankedDocument::getScore).reversed());

        return results;
    }
}