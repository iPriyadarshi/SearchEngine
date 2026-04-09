package com.searchengine.ranking.core;

import com.searchengine.api.Index;
import com.searchengine.api.Ranker;
import com.searchengine.index.model.Posting;
import com.searchengine.index.model.PostingList;
import com.searchengine.query.result.RankedDocument;

import java.util.*;

public class TFIDFRanker implements Ranker {

    private final Index index;

    public TFIDFRanker(Index index) {
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

            double idf = computeIDF(N, df);

            for (Posting posting : postingList.getPostings()) {

                int docId = posting.getDocId();
                int tf = posting.getTermFrequency();

                double tfWeight = computeTF(tf);

                double tfidf = tfWeight * idf;

                scores.merge(docId, tfidf, Double::sum);
            }
        }

        return sortResults(scores);
    }

    private double computeTF(int tf) {

        /*
         SEIR recommends log normalization:

         tf = 1 + log(tf)

         prevents very frequent terms dominating
        */

        return 1 + Math.log(tf);
    }

    private double computeIDF(int N, int df) {

        /*
         standard IDF
         */

        return Math.log((double) N / df);
    }

    private List<RankedDocument> sortResults(Map<Integer, Double> scores) {

        List<RankedDocument> results = new ArrayList<>();

        for (Map.Entry<Integer, Double> e : scores.entrySet()) {

            results.add(new RankedDocument(e.getKey(), e.getValue()));
        }

        results.sort(Comparator.comparingDouble(RankedDocument::getScore).reversed());

        return results;
    }
}