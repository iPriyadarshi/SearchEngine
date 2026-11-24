package com.searchengine.web;

import com.searchengine.document.model.Document;
import com.searchengine.document.model.Link;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CorpusTest {

    @Test
    void roundTripsPagesAcrossFilesIncludingLongText(@TempDir Path dir) throws Exception {

        String longText = "word ".repeat(30_000);

        try (Corpus.Writer writer = new Corpus.Writer(dir.resolve("a" + Corpus.SUFFIX))) {

            writer.write(new Document(0, "Tea", "About tea", longText, "https://tea.example/",
                    List.of(new Link("https://tea.example/green", "green tea"))));

            writer.commit();
        }

        try (Corpus.Writer writer = new Corpus.Writer(dir.resolve("b" + Corpus.SUFFIX))) {

            writer.write(new Document(0, "Coffee", "", "Coffee text", "https://coffee.example/", List.of()));

            writer.commit();
        }

        // An uncommitted file must leave nothing behind.
        try (Corpus.Writer writer = new Corpus.Writer(dir.resolve("c" + Corpus.SUFFIX))) {

            writer.write(new Document(0, "Partial", "", "x", "https://partial.example/", List.of()));
        }

        assertFalse(Files.exists(dir.resolve("c" + Corpus.SUFFIX)));

        assertEquals(2, Corpus.files(dir).size());

        List<Document> docs = new ArrayList<>();

        try (Corpus.Reader reader = Corpus.read(Corpus.files(dir))) {

            reader.forEachRemaining(docs::add);
        }

        assertEquals(2, docs.size());

        Document tea = docs.get(0);

        assertEquals("Tea", tea.getTitle());

        assertEquals("About tea", tea.getDescription());

        assertEquals(longText, tea.getContent());

        assertEquals(List.of(new Link("https://tea.example/green", "green tea")), tea.getLinks());

        assertEquals("https://coffee.example/", docs.get(1).getPath());

        assertTrue(Files.list(dir).noneMatch(p -> p.toString().endsWith(".part")));
    }

    @Test
    void namesCorpusFilesAfterTheirWarc() {

        assertEquals("CC-MAIN-x-00001" + Corpus.SUFFIX,
                Corpus.nameFor("https://data.commoncrawl.org/crawl-data/c/segments/s/warc/CC-MAIN-x-00001.warc.gz"));
    }
}
