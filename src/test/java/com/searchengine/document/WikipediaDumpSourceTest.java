package com.searchengine.document;

import com.searchengine.document.model.Document;
import com.searchengine.document.source.WikipediaDumpSource;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WikipediaDumpSourceTest {

    private static final String DUMP = """
            <mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/" version="0.11">
              <siteinfo>
                <sitename>Wikipedia</sitename>
                <base>https://simple.wikipedia.org/wiki/Main_Page</base>
              </siteinfo>
              <page>
                <title>Search engine</title>
                <ns>0</ns>
                <id>1</id>
                <revision><id>10</id><text bytes="80" xml:space="preserve">A '''search engine''' finds [[web page|pages]]. &lt;ref&gt;cite&lt;/ref&gt;</text></revision>
              </page>
              <page>
                <title>Searching</title>
                <ns>0</ns>
                <redirect title="Search engine" />
                <revision><text>#REDIRECT [[Search engine]]</text></revision>
              </page>
              <page>
                <title>Talk:Search engine</title>
                <ns>1</ns>
                <revision><text>discussion</text></revision>
              </page>
              <page>
                <title>Inverted index</title>
                <ns>0</ns>
                <revision><text>An index &amp;quot;inverted&amp;quot; maps terms to documents.</text></revision>
              </page>
            </mediawiki>
            """;

    @Test
    void readsArticlesAndSkipsRedirectsAndOtherNamespaces(@TempDir Path dir) throws Exception {

        Path dump = dir.resolve("dump.xml");

        Files.writeString(dump, DUMP);

        List<Document> docs = new WikipediaDumpSource(dump).loadDocuments();

        assertEquals(2, docs.size());

        Document first = docs.get(0);

        assertEquals(0, first.getId());

        assertEquals("Search engine", first.getTitle());

        assertEquals("https://simple.wikipedia.org/wiki/Search_engine", first.getPath());

        assertEquals("Search engine\n\nA search engine finds pages.", first.getContent());

        assertEquals("Inverted index", docs.get(1).getTitle());

        assertTrue(docs.get(1).getContent().contains("An index \"inverted\" maps"), docs.get(1).getContent());
    }

    @Test
    void readsBzip2DumpsAndHonoursMaxDocuments(@TempDir Path dir) throws Exception {

        Path dump = dir.resolve("dump.xml.bz2");

        try (OutputStream out = new BZip2CompressorOutputStream(Files.newOutputStream(dump))) {

            out.write(DUMP.getBytes(StandardCharsets.UTF_8));
        }

        List<Document> docs = new WikipediaDumpSource(dump, 1).loadDocuments();

        assertEquals(1, docs.size());

        assertEquals("Search engine", docs.get(0).getTitle());
    }
}
