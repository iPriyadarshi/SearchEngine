package com.searchengine.query.disk;

import com.searchengine.api.DocIdIterator;
import com.searchengine.api.PostingsIterator;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.TermInfo;
import com.searchengine.query.disk.TopDocs.ScoredDoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Ranked top-k retrieval over a {@link DiskIndex} using the MaxScore
 * algorithm (Turtle &amp; Flood, 1995).
 * <p>
 * Query terms are sorted by their maximum possible score. Once the heap holds
 * k documents, its minimum score θ is the bar a new document must clear. The
 * longest prefix of terms whose summed maximum scores is ≤ θ is
 * <em>non-essential</em>: a document containing only those terms cannot
 * qualify. Candidates are therefore drawn only from the essential terms, and
 * the non-essential lists are merely {@link DocIdIterator#advance probed} for
 * candidates - skipping straight past most of their postings. Common words
 * ("history", "state") quickly become non-essential, which is what keeps
 * queries over millions of documents fast.
 * <p>
 * The result is exact: identical to scoring every matching document.
 */
public final class TopKSearcher {

    // Heap order: worst first. Among equal scores the larger doc id is worse,
    // so ties resolve to the smaller id, as in a full sort.
    private static final Comparator<ScoredDoc> WORST_FIRST =
            Comparator.comparingDouble(ScoredDoc::score).thenComparing(ScoredDoc::docId, Comparator.reverseOrder());

    private final DiskIndex index;

    public TopKSearcher(DiskIndex index) {

        this.index = index;
    }

    /**
     * One scoring clause: an index term (possibly a field term such as
     * "~python"), how to score it, and a weight.
     */
    public record WeightedTerm(String term, Similarity similarity, double boost) {
    }

    /**
     * @param terms analyzed query terms; repeated terms count once per occurrence
     */
    public TopDocs search(List<String> terms, Similarity similarity, int k) {

        Map<String, Integer> counts = new LinkedHashMap<>();

        for (String term : terms) {

            counts.merge(term, 1, Integer::sum);
        }

        List<WeightedTerm> weighted = new ArrayList<>();

        counts.forEach((term, count) -> weighted.add(new WeightedTerm(term, similarity, count)));

        return search(weighted, k);
    }

    /**
     * Top k documents by the sum of the clauses' scores. Clauses for terms
     * that are not indexed are ignored.
     */
    public TopDocs search(List<WeightedTerm> terms, int k) {

        if (k <= 0) {

            return TopDocs.empty();
        }

        List<Clause> clauses = new ArrayList<>();

        for (WeightedTerm term : terms) {

            TermInfo info = index.termInfo(term.term());

            if (info != null && term.boost() > 0) {

                Similarity.TermWeight weight = term.similarity().weight(info, index.numDocs(),
                        index.averageDocLength(), term.boost());

                clauses.add(new Clause(index.postings(info), weight));
            }
        }

        if (clauses.isEmpty()) {

            return TopDocs.empty();
        }

        clauses.sort(Comparator.comparingDouble(c -> c.weight.maxScore()));

        return maxScore(clauses.toArray(new Clause[0]), k);
    }

    private TopDocs maxScore(Clause[] clauses, int k) {

        int n = clauses.length;

        // upTo[i] = sum of max scores of clauses 0..i.
        double[] upTo = new double[n];

        double running = 0;

        for (int i = 0; i < n; i++) {

            running += clauses[i].weight.maxScore();

            upTo[i] = running;
        }

        for (Clause clause : clauses) {

            clause.postings.nextDoc();
        }

        PriorityQueue<ScoredDoc> heap = new PriorityQueue<>(Math.min(k, 1024) + 1, WORST_FIRST);

        double threshold = Double.NEGATIVE_INFINITY;

        int firstEssential = 0;

        long scored = 0;

        while (firstEssential < n) {

            int doc = DocIdIterator.NO_MORE_DOCS;

            for (int i = firstEssential; i < n; i++) {

                doc = Math.min(doc, clauses[i].postings.docId());
            }

            if (doc == DocIdIterator.NO_MORE_DOCS) {

                break;
            }

            int docLength = index.docLength(doc);

            double score = 0;

            for (int i = firstEssential; i < n; i++) {

                PostingsIterator postings = clauses[i].postings;

                if (postings.docId() == doc) {

                    score += clauses[i].weight.score(postings.freq(), docLength);

                    postings.nextDoc();
                }
            }

            // Add non-essential terms, best bound first, while the doc can still qualify.
            boolean competitive = true;

            for (int i = firstEssential - 1; i >= 0; i--) {

                if (score + upTo[i] <= threshold) {

                    competitive = false;

                    break;
                }

                PostingsIterator postings = clauses[i].postings;

                if (postings.docId() < doc) {

                    postings.advance(doc);
                }

                if (postings.docId() == doc) {

                    score += clauses[i].weight.score(postings.freq(), docLength);
                }
            }

            scored++;

            // Later docs have larger ids and lose ties, so only a strictly better score enters.
            if (competitive && (heap.size() < k || score > threshold)) {

                heap.add(new ScoredDoc(doc, score));

                if (heap.size() > k) {

                    heap.poll();
                }

                if (heap.size() == k) {

                    threshold = heap.peek().score();

                    while (firstEssential < n && upTo[firstEssential] <= threshold) {

                        firstEssential++;
                    }
                }
            }
        }

        ScoredDoc[] hits = heap.toArray(new ScoredDoc[0]);

        Arrays.sort(hits, WORST_FIRST.reversed());

        // Every match was scored only if no term ever became non-essential.
        long total = firstEssential == 0 ? scored : -1;

        return new TopDocs(List.of(hits), total, scored);
    }

    private record Clause(PostingsIterator postings, Similarity.TermWeight weight) {
    }
}
