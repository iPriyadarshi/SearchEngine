package com.searchengine.document;

import com.searchengine.document.model.Document;
import com.searchengine.document.source.HtmlDocumentSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HtmlDocumentSourceTest {

    @Test
    void shouldExtractTitleAndBodyTextAndIgnoreNonHtml(@TempDir Path dir) throws IOException {

        Files.writeString(dir.resolve("page.html"),
                "<html><head><title>Search Engines</title></head>"
                        + "<body><h1>Inverted Index</h1><p>ranking and retrieval</p>"
                        + "<script>var x = 1;</script></body></html>");

        Files.writeString(dir.resolve("notes.txt"), "should be ignored");

        List<Document> docs = new HtmlDocumentSource(dir.toString()).loadDocuments();

        assertEquals(1, docs.size());

        String content = docs.getFirst().getContent();

        assertTrue(content.contains("Search Engines"));

        assertTrue(content.contains("Inverted Index"));

        assertTrue(content.contains("ranking and retrieval"));

        // script contents must not leak into the indexable text
        assertTrue(!content.contains("var x"));
    }
}
