package com.searchengine.index.core;

import com.searchengine.api.DocumentIdProvider;
import com.searchengine.api.DocumentLengthProvider;
import com.searchengine.api.Index;
import com.searchengine.document.model.Document;
import com.searchengine.index.model.PositionalPosting;
import com.searchengine.index.model.PositionalPostingList;
import com.searchengine.index.model.PostingList;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inverted index that records the position of every term occurrence, enabling
 * phrase and proximity queries. It also implements {@link Index} and
 * {@link DocumentLengthProvider} so it is a drop-in replacement for
 * {@link MemoryInvertedIndex} and works with all existing rankers.
 */
public class PositionalInvertedIndex implements Index, DocumentLengthProvider, DocumentIdProvider {

    private final Map<String, PositionalPostingList> index = new HashMap<>();

    private final Map<Integer, Integer> documentLengths = new HashMap<>();

    private int totalDocuments = 0;

    private long totalTokens = 0;

    @Override
    public void addDocument(Document doc, List<String> tokens) {

        totalDocuments++;

        totalTokens += tokens.size();

        documentLengths.merge(doc.getId(), tokens.size(), Integer::sum);

        int position = 0;

        for (String token : tokens) {

            index.computeIfAbsent(token, k -> new PositionalPostingList())
                    .add(doc.getId(), position);

            position++;
        }
    }

    @Override
    public PostingList getPostingList(String term) {

        PositionalPostingList list = index.get(term);

        return list == null ? new PostingList() : list.toPostingList();
    }

    /**
     * Positional posting list for a term, or {@code null} if the term is
     * unknown. Used by phrase and proximity query executors.
     */
    public PositionalPostingList getPositionalPostingList(String term) {

        return index.get(term);
    }

    /**
     * All positional postings for a term, or an empty collection if the term is
     * unknown (never {@code null}).
     */
    public Collection<PositionalPosting> getPositionalPostings(String term) {

        PositionalPostingList list = index.get(term);

        return list == null ? List.of() : list.getPostings();
    }

    public List<Integer> getPositions(String term, int docId) {

        PositionalPostingList list = index.get(term);

        return list == null ? List.of() : list.getPositions(docId);
    }

    @Override
    public int getDocumentFrequency(String term) {

        PositionalPostingList list = index.get(term);

        return list == null ? 0 : list.documentFrequency();
    }

    @Override
    public int getTotalDocuments() {

        return totalDocuments;
    }

    @Override
    public int getDocumentLength(int docId) {

        return documentLengths.getOrDefault(docId, 0);
    }

    @Override
    public double getAverageDocumentLength() {

        if (totalDocuments == 0) {

            return 0.0;
        }

        return (double) totalTokens / totalDocuments;
    }

    @Override
    public Set<Integer> documentIds() {

        return new HashSet<>(documentLengths.keySet());
    }

    public Set<String> vocabulary() {

        return index.keySet();
    }
}
