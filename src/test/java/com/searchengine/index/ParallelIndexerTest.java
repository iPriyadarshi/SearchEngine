package com.searchengine.index;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.index.disk.ParallelIndexer;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.filter.PorterStemmerFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ParallelIndexerTest {

    private static final Parser PARSER = new DefaultParser(new RegexTokenizer(),
            List.of(new LowercaseFilter(), new PorterStemmerFilter()));

    private static final List<String> INDEX_FILES = List.of("terms.dict", "terms.idx", "postings.doc",
            "postings.pos", "doclens.bin", "docs.dat", "docs.idx");

    @TempDir
    Path dir;

    private static List<Document> corpus() {

        Random random = new Random(3);

        String[] words = {"index", "indexing", "search", "searched", "rank", "ranking", "query", "document"};

        List<Document> docs = new ArrayList<>();

        for (int d = 0; d < 2000; d++) {

            StringBuilder text = new StringBuilder();

            for (int i = 0, n = 1 + random.nextInt(40); i < n; i++) {

                text.append(words[random.nextInt(words.length)]).append(' ').append("t").append(random.nextInt(900)).append(' ');
            }

            docs.add(new Document(d, "doc " + d, text.toString(), "p" + d));
        }

        return docs;
    }

    @Test
    void parallelBuildIsByteIdenticalToSequentialBuild() throws Exception {

        List<Document> docs = corpus();

        Path sequential = dir.resolve("seq");

        try (DiskIndexWriter writer = new DiskIndexWriter(sequential, PARSER, 32 * 1024)) {

            for (Document doc : docs) {

                writer.add(doc);
            }
        }

        Path parallel = dir.resolve("par");

        try (DiskIndexWriter writer = new DiskIndexWriter(parallel, PARSER, 32 * 1024)) {

            int added = ParallelIndexer.index(docs.iterator(), Function.identity(), PARSER, writer, 6, Long.MAX_VALUE);

            assertEquals(docs.size(), added);
        }

        for (String file : INDEX_FILES) {

            assertArrayEquals(Files.readAllBytes(sequential.resolve(file)), Files.readAllBytes(parallel.resolve(file)), file);
        }
    }

    @Test
    void skipsNullDocumentsAndHonoursMaxDocs() throws Exception {

        List<Document> docs = corpus();

        try (DiskIndexWriter writer = new DiskIndexWriter(dir.resolve("max"), PARSER, 1 << 20)) {

            // Every third item "cleans" to nothing.
            int added = ParallelIndexer.index(docs.iterator(), d -> d.getId() % 3 == 0 ? null : d, PARSER, writer, 3, 500);

            assertEquals(500, added);

            assertEquals(500, writer.documentCount());
        }
    }

    @Test
    void workerFailuresPropagate() throws Exception {

        try (DiskIndexWriter writer = new DiskIndexWriter(dir.resolve("fail"), PARSER, 1 << 20)) {

            assertThrows(java.io.IOException.class, () -> ParallelIndexer.index(corpus().iterator(), d -> {

                throw new IllegalStateException("boom");

            }, PARSER, writer, 2, Long.MAX_VALUE));
        }
    }
}
