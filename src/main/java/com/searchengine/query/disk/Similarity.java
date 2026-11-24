package com.searchengine.query.disk;

import com.searchengine.index.disk.TermInfo;

/**
 * Scoring function for disk-index retrieval. For each query term it produces
 * a {@link TermWeight} that scores a (frequency, document length) pair and
 * reports an upper bound on that score, which is what lets
 * {@link TopKSearcher} skip documents that cannot reach the top k.
 * <p>
 * The three implementations reproduce the in-memory rankers exactly
 * ({@code TFIDFRanker}, {@code CosineSimilarityRanker}, {@code BM25Ranker}),
 * so results are identical on either index.
 */
public interface Similarity {

    /**
     * @param boost multiplier for a term repeated in the query (the in-memory
     *              rankers add its contribution once per repetition)
     */
    TermWeight weight(TermInfo term, int numDocs, double averageDocLength, int boost);

    interface TermWeight {

        double score(int freq, int docLength);

        /**
         * Highest score this term can give any document.
         */
        double maxScore();
    }

    /**
     * {@code (1 + ln tf) * ln(N / df)}
     */
    static Similarity tfIdf() {

        return (term, numDocs, avgDl, boost) -> {

            double idf = Math.log((double) numDocs / term.docFreq());

            return weight((freq, len) -> boost * (1 + Math.log(freq)) * idf, term);
        };
    }

    /**
     * {@code tf * ln(N / df)}: the un-normalized dot product used by the
     * in-memory "cosine" ranker.
     */
    static Similarity cosine() {

        return (term, numDocs, avgDl, boost) -> {

            double idf = Math.log((double) numDocs / term.docFreq());

            return weight((freq, len) -> boost * freq * idf, term);
        };
    }

    static Similarity bm25() {

        return bm25(1.5, 0.75);
    }

    /**
     * Okapi BM25 with {@code IDF = ln(1 + (N - df + 0.5) / (df + 0.5))}.
     */
    static Similarity bm25(double k1, double b) {

        return (term, numDocs, avgDl, boost) -> {

            int df = term.docFreq();

            double idf = Math.log(1 + (numDocs - df + 0.5) / (df + 0.5));

            return weight((freq, len) -> {

                double norm = k1 * (1 - b + b * (len / avgDl));

                return boost * idf * (freq * (k1 + 1)) / (freq + norm);

            }, term);
        };
    }

    /**
     * Every supported score grows with frequency and shrinks (or is flat) with
     * document length, so the bound is the score at (max freq, min length).
     */
    private static TermWeight weight(ScoreFunction function, TermInfo term) {

        double max = function.score(term.maxFreq(), term.minDocLen());

        return new TermWeight() {

            @Override
            public double score(int freq, int docLength) {

                return function.score(freq, docLength);
            }

            @Override
            public double maxScore() {

                return max;
            }
        };
    }

    @FunctionalInterface
    interface ScoreFunction {

        double score(int freq, int docLength);
    }
}
