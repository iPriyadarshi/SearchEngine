package com.searchengine.index;

import com.searchengine.index.model.Posting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PostingTest {

    @Test
    void testInitialTermFrequencyIsOne() {
        Posting posting = new Posting(5);
        assertEquals(1, posting.getTermFrequency());
    }

    @Test
    void testGetDocId() {
        Posting posting = new Posting(99);
        assertEquals(99, posting.getDocId());
    }

    @Test
    void testIncrementFrequency() {
        Posting posting = new Posting(1);
        posting.incrementFrequency();
        assertEquals(2, posting.getTermFrequency());
    }

    @Test
    void testMultipleIncrements() {
        Posting posting = new Posting(3);
        posting.incrementFrequency();
        posting.incrementFrequency();
        posting.incrementFrequency();
        assertEquals(4, posting.getTermFrequency());
    }
}
