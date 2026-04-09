package com.searchengine.index;

import com.searchengine.index.model.Posting;
import com.searchengine.index.model.PostingList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PostingListAdditionalTest {

    @Test
    void testEmptyListHasZeroDocumentFrequency() {
        PostingList list = new PostingList();
        assertEquals(0, list.documentFrequency());
    }

    @Test
    void testTermFrequencyForMissingDoc() {
        PostingList list = new PostingList();
        list.add(1);
        assertEquals(0, list.termFrequency(99));
    }

    @Test
    void testGetPostingsContainsAllDocIds() {
        PostingList list = new PostingList();
        list.add(1);
        list.add(2);
        list.add(3);

        List<Posting> postings = list.getPostings();
        assertEquals(3, postings.size());
    }

    @Test
    void testGetPostingsReflectsFrequency() {
        PostingList list = new PostingList();
        list.add(10);
        list.add(10);
        list.add(10);

        List<Posting> postings = list.getPostings();
        assertEquals(1, postings.size());
        assertEquals(3, postings.get(0).getTermFrequency());
    }

    @Test
    void testSingleEntry() {
        PostingList list = new PostingList();
        list.add(7);

        assertEquals(1, list.documentFrequency());
        assertEquals(1, list.termFrequency(7));
    }

    @Test
    void testGetPostingsIsDefensiveCopy() {
        PostingList list = new PostingList();
        list.add(1);

        List<Posting> first = list.getPostings();
        List<Posting> second = list.getPostings();

        assertNotSame(first, second);
    }
}
