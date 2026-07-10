package com.searchengine.query.executor;

import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.model.Query;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Executes phrase queries against a positional index. A document matches only
 * when the query terms appear consecutively and in order. The score is the
 * number of times the phrase occurs in the document.
 */
public class PhraseQueryExecutor {

    private final PositionalInvertedIndex index;

    public PhraseQueryExecutor(PositionalInvertedIndex index) {

        this.index = index;
    }

    public SearchResult execute(Query query) {

        if (!(query instanceof PhraseQuery phraseQuery)) {

            throw new IllegalArgumentException("PhraseQueryExecutor requires a PhraseQuery");
        }

        return new SearchResult(match(phraseQuery.getTerms()));
    }

    private List<RankedDocument> match(List<String> terms) {

        List<RankedDocument> results = new ArrayList<>();

        if (terms.isEmpty()) {

            return results;
        }

        // A single-term "phrase" degrades to a normal term lookup.
        if (terms.size() == 1) {

            for (var posting : index.getPositionalPostings(terms.getFirst())) {

                results.add(new RankedDocument(posting.getDocId(), posting.getTermFrequency()));
            }

            results.sort(Comparator.comparingDouble(RankedDocument::getScore).reversed());

            return results;
        }

        // Candidate documents = those containing the (likely rarest) first term.
        for (var firstPosting : index.getPositionalPostings(terms.getFirst())) {

            int docId = firstPosting.getDocId();

            int occurrences = countPhrase(terms, docId);

            if (occurrences > 0) {

                results.add(new RankedDocument(docId, occurrences));
            }
        }

        results.sort(Comparator.comparingDouble(RankedDocument::getScore).reversed());

        return results;
    }

    /**
     * Count how many times the ordered phrase occurs in the document by
     * anchoring on each position of the first term and verifying that every
     * subsequent term sits at the next consecutive position.
     */
    private int countPhrase(List<String> terms, int docId) {

        int occurrences = 0;

        for (int start : index.getPositions(terms.getFirst(), docId)) {

            boolean matched = true;

            for (int i = 1; i < terms.size(); i++) {

                if (!index.getPositions(terms.get(i), docId).contains(start + i)) {

                    matched = false;

                    break;
                }
            }

            if (matched) {

                occurrences++;
            }
        }

        return occurrences;
    }
}
