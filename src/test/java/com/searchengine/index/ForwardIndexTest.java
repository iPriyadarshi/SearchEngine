package com.searchengine.index;

import com.searchengine.index.core.ForwardIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForwardIndexTest {

    @Test
    void testForwardIndex() {

        ForwardIndex index = new ForwardIndex();

        index.addDocument(1, List.of("search", "engine", "search"));

        assertEquals(2, index.getTermFrequency(1, "search"));

        assertEquals(1, index.getTermFrequency(1, "engine"));
    }
}