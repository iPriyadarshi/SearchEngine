package com.searchengine.document;

import com.searchengine.document.model.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DocumentTest {

    @Test
    void testGetId() {
        Document doc = new Document(42, "some content", "/path/to/file.txt");
        assertEquals(42, doc.getId());
    }

    @Test
    void testGetContent() {
        Document doc = new Document(1, "hello world", "/path/doc.txt");
        assertEquals("hello world", doc.getContent());
    }

    @Test
    void testGetPath() {
        Document doc = new Document(1, "content", "/data/docs/file.txt");
        assertEquals("/data/docs/file.txt", doc.getPath());
    }

    @Test
    void testToStringContainsIdAndPath() {
        Document doc = new Document(7, "text", "/docs/seven.txt");
        String str = doc.toString();
        assertTrue(str.contains("7"));
        assertTrue(str.contains("/docs/seven.txt"));
    }

    @Test
    void testEmptyContent() {
        Document doc = new Document(0, "", "/empty.txt");
        assertEquals("", doc.getContent());
    }
}
