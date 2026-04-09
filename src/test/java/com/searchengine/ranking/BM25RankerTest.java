package com.searchengine.ranking;

import com.searchengine.index.core.MemoryInvertedIndex;
import com.searchengine.ranking.core.BM25Ranker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BM25RankerTest {

    @Test
    void testBM25RankerThrowsUnsupportedOperationException() {
        MemoryInvertedIndex index = new MemoryInvertedIndex();
        BM25Ranker ranker = new BM25Ranker(index);
        assertThrows(UnsupportedOperationException.class, () -> ranker.rank(List.of("search")));
    }
}
