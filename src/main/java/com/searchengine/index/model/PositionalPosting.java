package com.searchengine.index.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A posting that also remembers every position at which the term occurs in the
 * document (positions are token offsets in the analyzed token stream).
 */
public class PositionalPosting {

    private final int docId;

    private final List<Integer> positions = new ArrayList<>();

    public PositionalPosting(int docId) {

        this.docId = docId;
    }

    public void addPosition(int position) {

        positions.add(position);
    }

    public int getDocId() {

        return docId;
    }

    public List<Integer> getPositions() {

        return Collections.unmodifiableList(positions);
    }

    public int getTermFrequency() {

        return positions.size();
    }
}
