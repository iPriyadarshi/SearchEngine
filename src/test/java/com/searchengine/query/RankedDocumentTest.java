package com.searchengine.query;

import com.searchengine.query.result.RankedDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RankedDocumentTest {

    @Test
    void testGetDocId() {
        RankedDocument rd = new RankedDocument(42, 0.75);
        assertEquals(42, rd.getDocId());
    }

    @Test
    void testGetScore() {
        RankedDocument rd = new RankedDocument(1, 3.14);
        assertEquals(3.14, rd.getScore(), 1e-9);
    }

    @Test
    void testToStringContainsDocIdAndScore() {
        RankedDocument rd = new RankedDocument(5, 1.23);
        String str = rd.toString();
        assertTrue(str.contains("5"));
        assertTrue(str.contains("1.23"));
    }

    @Test
    void testZeroScore() {
        RankedDocument rd = new RankedDocument(0, 0.0);
        assertEquals(0.0, rd.getScore(), 1e-9);
    }
}
