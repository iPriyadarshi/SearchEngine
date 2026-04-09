package com.searchengine.document;

import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FolderDocumentSourceAdditionalTest {

    @Test
    void testEmptyFolderReturnsNoDocs() throws Exception {
        Path tempDir = Files.createTempDirectory("empty_docs");
        FolderDocumentSource source = new FolderDocumentSource(tempDir.toString());
        List<Document> docs = source.loadDocuments();
        assertTrue(docs.isEmpty());
    }

    @Test
    void testMultipleFilesAreLoaded() throws Exception {
        Path tempDir = Files.createTempDirectory("multi_docs");
        Files.writeString(tempDir.resolve("a.txt"), "content a");
        Files.writeString(tempDir.resolve("b.txt"), "content b");
        Files.writeString(tempDir.resolve("c.txt"), "content c");

        FolderDocumentSource source = new FolderDocumentSource(tempDir.toString());
        List<Document> docs = source.loadDocuments();

        assertEquals(3, docs.size());
    }

    @Test
    void testDocIdsAreSequential() throws Exception {
        Path tempDir = Files.createTempDirectory("seq_docs");
        Files.writeString(tempDir.resolve("x.txt"), "x");
        Files.writeString(tempDir.resolve("y.txt"), "y");

        FolderDocumentSource source = new FolderDocumentSource(tempDir.toString());
        List<Document> docs = source.loadDocuments();

        assertEquals(2, docs.size());
        // IDs should be 0 and 1 (in some order)
        int sumIds = docs.stream().mapToInt(Document::getId).sum();
        assertEquals(1, sumIds);
    }

    @Test
    void testSubdirectoriesAreIgnored() throws Exception {
        Path tempDir = Files.createTempDirectory("dir_docs");
        Files.writeString(tempDir.resolve("file.txt"), "text");
        Files.createDirectory(tempDir.resolve("subdir"));

        FolderDocumentSource source = new FolderDocumentSource(tempDir.toString());
        List<Document> docs = source.loadDocuments();

        assertEquals(1, docs.size());
    }

    @Test
    void testFilePathIsStoredInDocument() throws Exception {
        Path tempDir = Files.createTempDirectory("path_docs");
        Path file = tempDir.resolve("doc.txt");
        Files.writeString(file, "hello");

        FolderDocumentSource source = new FolderDocumentSource(tempDir.toString());
        List<Document> docs = source.loadDocuments();

        assertEquals(1, docs.size());
        assertTrue(docs.get(0).getPath().contains("doc.txt"));
    }
}
