package com.searchengine.document;

import com.searchengine.document.model.Document;
import com.searchengine.document.source.FolderDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FolderDocumentSourceTest {

    @Test
    void testLoadsFiles() throws Exception {

        Path tempDir = Files.createTempDirectory("docs");

        Path file = tempDir.resolve("doc.txt");

        Files.writeString(file, "hello world");

        FolderDocumentSource source = new FolderDocumentSource(tempDir.toString());

        List<Document> docs = source.loadDocuments();

        assertEquals(1, docs.size());

        assertEquals("hello world", docs.get(0).getContent());
    }
}