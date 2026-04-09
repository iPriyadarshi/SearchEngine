package com.searchengine.ranking;

import com.searchengine.ranking.stats.DocumentStatistics;
import com.searchengine.ranking.stats.TermStatistics;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RankingStatsTest {

    @Test
    void testDocumentStatisticsGetDocId() {
        DocumentStatistics stats = new DocumentStatistics(7, Map.of("java", 3), 10);
        assertEquals(7, stats.getDocId());
    }

    @Test
    void testDocumentStatisticsGetTermFrequencies() {
        Map<String, Integer> freqs = Map.of("search", 2, "engine", 1);
        DocumentStatistics stats = new DocumentStatistics(1, freqs, 3);
        assertEquals(freqs, stats.getTermFrequencies());
    }

    @Test
    void testDocumentStatisticsGetLength() {
        DocumentStatistics stats = new DocumentStatistics(1, Map.of(), 42);
        assertEquals(42, stats.getLength());
    }

    @Test
    void testTermStatisticsGetTerm() {
        TermStatistics stats = new TermStatistics("java", 10);
        assertEquals("java", stats.getTerm());
    }

    @Test
    void testTermStatisticsGetDocumentFrequency() {
        TermStatistics stats = new TermStatistics("search", 25);
        assertEquals(25, stats.getDocumentFrequency());
    }
}
