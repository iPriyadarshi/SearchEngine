package com.searchengine.ranking.core;

import com.searchengine.api.DocumentLengthProvider;
import com.searchengine.api.Index;
import com.searchengine.api.Ranker;
import com.searchengine.index.model.Posting;
import com.searchengine.index.model.PostingList;
import com.searchengine.query.result.RankedDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Okapi BM25 ranking function.
 * <p>
 * BM25 improves on plain TF-IDF by saturating term frequency (via k1) and
 * normalizing for document length (via b), which prevents long documents from
 * being unfairly favoured.
 *
 * <pre>
 * score(D, Q) = &Sigma; IDF(qi) * ( f(qi,D) * (k1 + 1) )
 *                        / ( f(qi,D) + k1 * (1 - b + b * |D| / avgdl) )
 *
 * IDF(qi)    = ln( 1 + (N - n(qi) + 0.5) / (n(qi) + 0.5) )
 * </pre>
 */
public class BM25Ranker implements Ranker {

    private static final double DEFAULT_K1 = 1.5;

    private static final double DEFAULT_B = 0.75;

    private final Index index;

    private final DocumentLengthProvider lengths;

    private final double k1;

    private final double b;

    /**
     * Convenience constructor for indexes that also provide document lengths
     * (e.g. {@link com.searchengine.index.core.MemoryInvertedIndex}).
     */
    public BM25Ranker(Index index) {

        this(index, asLengthProvider(index), DEFAULT_K1, DEFAULT_B);
    }

    public BM25Ranker(Index index, DocumentLengthProvider lengths) {

        this(index, lengths, DEFAULT_K1, DEFAULT_B);
    }

    public BM25Ranker(Index index, DocumentLengthProvider lengths, double k1, double b) {

        this.index = index;

        this.lengths = lengths;

        this.k1 = k1;

        this.b = b;
    }

    private static DocumentLengthProvider asLengthProvider(Index index) {

        if (index instanceof DocumentLengthProvider provider) {

            return provider;
        }

        throw new IllegalArgumentException(
                "BM25 requires an index that provides document lengths; "
                        + "pass a DocumentLengthProvider explicitly");
    }

    @Override
    public List<RankedDocument> rank(List<String> queryTerms) {

        Map<Integer, Double> scores = new HashMap<>();

        int n = index.getTotalDocuments();

        double avgdl = lengths.getAverageDocumentLength();

        for (String term : queryTerms) {

            PostingList postingList = index.getPostingList(term);

            if (postingList == null) {

                continue;
            }

            int df = postingList.documentFrequency();

            if (df == 0) {

                continue;
            }

            double idf = computeIDF(n, df);

            for (Posting posting : postingList.getPostings()) {

                int docId = posting.getDocId();

                double f = posting.getTermFrequency();

                double dl = lengths.getDocumentLength(docId);

                double denom = f + k1 * (1 - b + b * (dl / avgdl));

                double score = idf * (f * (k1 + 1)) / denom;

                scores.merge(docId, score, Double::sum);
            }
        }

        return sortResults(scores);
    }

    private double computeIDF(int n, int df) {

        return Math.log(1 + (n - df + 0.5) / (df + 0.5));
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
