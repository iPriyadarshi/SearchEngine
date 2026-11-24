package com.searchengine.index;

import com.searchengine.Main;
import com.searchengine.api.PostingsIterator;
import com.searchengine.document.model.Document;
import com.searchengine.document.model.Link;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.index.disk.IndexFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field terms, anchor text, PageRank, domain ranks and de-duplication on a
 * small hand-made web.
 */
public class WebIndexTest {

    private static final String TEXT = "This page has enough ordinary text to count as a real document for the index. ".repeat(4);

    private static Document page(String url, String title, String text, Link... links) {

        return new Document(0, title, "", text, url, List.of(links));
    }

    @Test
    void indexesFieldsAnchorsAndLinkSignals(@TempDir Path dir) throws Exception {

        Path idx = dir.resolve("web");

        try (DiskIndexWriter writer = new DiskIndexWriter(idx, Main.buildParser(), 1 << 20)) {

            writer.setDeduplicate(true);

            writer.setDomainRanks(Map.of("python.org", 300, "example.org", 50_000));

            // 0: python home, linked from everyone.
            writer.add(page("https://www.python.org/", "Welcome to Python", "Home of the language. " + TEXT));

            // 1..3: pages that link to python.org with descriptive anchors.
            writer.add(page("https://blog.example.org/a", "A blog post", "Post one. " + TEXT,
                    new Link("https://www.python.org/", "official Python website"),
                    new Link("https://nowhere.example/", "an unindexed page")));

            writer.add(page("https://blog.example.org/b", "Another post", "Post two. " + TEXT,
                    new Link("https://www.python.org/", "download Python"),
                    new Link("https://blog.example.org/a", "click here")));

            writer.add(page("https://docs.python.org/3/tutorial/", "The Python Tutorial", "Tutorial. " + TEXT,
                    new Link("https://www.python.org/", "Python")));

            // Duplicate URL and duplicate text are both skipped.
            assertEquals(-1, writer.add(page("https://www.python.org/", "Again", "Different text here. " + TEXT)));

            assertEquals(-1, writer.add(page("https://mirror.example/b", "Copy", "Post two. " + TEXT)));

            assertEquals(2, writer.skippedDuplicates());
        }

        try (DiskIndex index = DiskIndex.open(idx)) {

            assertEquals(4, index.numDocs());

            // Title and URL fields.
            assertEquals(List.of(0, 3), docs(index.postings(IndexFiles.TITLE_FIELD + "python")));

            assertEquals(List.of(0, 3), docs(index.postings(IndexFiles.URL_FIELD + "python")));

            assertEquals(List.of(3), docs(index.postings(IndexFiles.URL_FIELD + "tutori")));

            // Anchor text lands on the target page, and generic anchors are dropped.
            assertEquals(List.of(0), docs(index.postings(IndexFiles.ANCHOR_FIELD + "offici")));

            assertEquals(List.of(0), docs(index.postings(IndexFiles.ANCHOR_FIELD + "download")));

            assertNull(index.postings(IndexFiles.ANCHOR_FIELD + "click"));

            assertNull(index.postings(IndexFiles.ANCHOR_FIELD + "unindex"));

            // The page everyone links to ranks highest; mean PageRank is 1.
            assertTrue(index.pageRank(0) > index.pageRank(1), "python.org should outrank a blog post");

            double sum = 0;

            for (int d = 0; d < 4; d++) {

                sum += index.pageRank(d);
            }

            assertEquals(4.0, sum, 1e-3);

            // Domain rank resolves sub-domains to their registered domain.
            assertEquals(300, index.domainRank(0));

            assertEquals(300, index.domainRank(3));

            assertEquals(50_000, index.domainRank(1));
        }
    }

    @Test
    void indexesWithoutLinksStillHaveUniformSignals(@TempDir Path dir) throws Exception {

        Path idx = dir.resolve("plain");

        try (DiskIndexWriter writer = new DiskIndexWriter(idx, Main.buildParser(), 1 << 20)) {

            writer.add(new Document(0, "plain text document", "a/b.txt"));
        }

        try (DiskIndex index = DiskIndex.open(idx)) {

            assertEquals(1f, index.pageRank(0));

            assertEquals(0, index.domainRank(0));

            assertNotNull(index.postings("plain"));
        }
    }

    @Test
    void urlWordsDropNoiseAndIds() {

        assertEquals("docs python 3 tutorial", DiskIndexWriter.urlWords("https://docs.python.org/3/tutorial/index.html"));

        assertEquals("news example item", DiskIndexWriter.urlWords("https://news.example.com/item/123456?id=9"));

        assertEquals("", DiskIndexWriter.urlWords("data/raw/doc1.txt"));
    }

    private static List<Integer> docs(PostingsIterator it) {

        assertNotNull(it);

        List<Integer> docs = new java.util.ArrayList<>();

        for (int d = it.nextDoc(); d != PostingsIterator.NO_MORE_DOCS; d = it.nextDoc()) {

            docs.add(d);
        }

        return docs;
    }
}
