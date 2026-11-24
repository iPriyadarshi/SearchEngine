package com.searchengine.query.disk;

import com.searchengine.api.DocIdIterator;
import com.searchengine.api.PostingsIterator;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.query.disk.TopDocs.ScoredDoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Exact phrase matching over a {@link DiskIndex}. Documents containing all
 * phrase terms are found by intersecting the postings (rarest first); only for
 * those are positions decoded and checked for consecutive occurrences. The
 * score is the number of phrase occurrences, as in the in-memory
 * {@code PhraseQueryExecutor}.
 */
public final class PhraseSearcher {

    private static final Comparator<ScoredDoc> WORST_FIRST =
            Comparator.comparingDouble(ScoredDoc::score).thenComparing(ScoredDoc::docId, Comparator.reverseOrder());

    private final DiskIndex index;

    public PhraseSearcher(DiskIndex index) {

        this.index = index;
    }

    /**
     * @param terms analyzed phrase terms, in phrase order
     */
    public TopDocs search(List<String> terms, int k) {

        if (terms.isEmpty() || k <= 0) {

            return TopDocs.empty();
        }

        // One iterator per phrase slot, so repeated terms work.
        PostingsIterator[] slots = new PostingsIterator[terms.size()];

        for (int i = 0; i < slots.length; i++) {

            slots[i] = index.postings(terms.get(i));

            if (slots[i] == null) {

                return TopDocs.empty();
            }
        }

        DocIdIterator matches = DocIdIterators.and(Arrays.asList(slots));

        PriorityQueue<ScoredDoc> heap = new PriorityQueue<>(k + 1, WORST_FIRST);

        long total = 0;

        long checked = 0;

        for (int doc = matches.nextDoc(); doc != DocIdIterator.NO_MORE_DOCS; doc = matches.nextDoc()) {

            checked++;

            int occurrences = slots.length == 1 ? slots[0].freq() : countPhrase(slots);

            if (occurrences == 0) {

                continue;
            }

            total++;

            heap.add(new ScoredDoc(doc, occurrences));

            if (heap.size() > k) {

                heap.poll();
            }
        }

        List<ScoredDoc> hits = new ArrayList<>(heap);

        hits.sort(WORST_FIRST.reversed());

        return new TopDocs(hits, total, checked);
    }

    /**
     * Count start positions p of slot 0 such that slot i occurs at p + i.
     */
    private static int countPhrase(PostingsIterator[] slots) {

        int[][] positions = new int[slots.length][];

        for (int i = 0; i < slots.length; i++) {

            positions[i] = slots[i].positions();
        }

        int count = 0;

        outer:
        for (int start : positions[0]) {

            for (int i = 1; i < positions.length; i++) {

                if (Arrays.binarySearch(positions[i], start + i) < 0) {

                    continue outer;
                }
            }

            count++;
        }

        return count;
    }
}
