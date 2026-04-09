package com.searchengine.index;

import com.searchengine.index.model.PostingList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PostingListTest {

    @Test
    void testTermFrequency() {

        PostingList list = new PostingList();

        list.add(1);
        list.add(1);
        list.add(2);

        assertEquals(2, list.termFrequency(1));

        assertEquals(1, list.termFrequency(2));
    }

    @Test
    void testDocumentFrequency() {

        PostingList list = new PostingList();

        list.add(1);
        list.add(1);
        list.add(2);

        assertEquals(2, list.documentFrequency());
    }
}