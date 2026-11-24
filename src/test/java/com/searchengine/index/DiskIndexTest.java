package com.searchengine.index;

import com.searchengine.api.Parser;
import com.searchengine.api.PostingsIterator;
import com.searchengine.document.model.Document;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.index.disk.StoredDocument;
import com.searchengine.index.disk.TermInfo;
import com.searchengine.index.model.PositionalPosting;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Builds the same corpus into the reference in-memory positional index and
 * into a disk index, then checks the disk postings match exactly.
 */
public class DiskIndexTest {

    private static final Parser PARSER = new DefaultParser(new RegexTokenizer(), List.of(new LowercaseFilter()));

    @TempDir
    Path tempDir;

    /**
     * Zipf-like corpus: a few very common terms (many blocks, skip tables) and
     * a long tail of rare ones (single block).
     */
    private static List<Document> corpus(int docs, long seed) {

        Random random = new Random(seed);

        List<Document> result = new ArrayList<>();

        for (int d = 0; d < docs; d++) {

            int length = 1 + random.nextInt(60);

            StringBuilder text = new StringBuilder();

            for (int i = 0; i < length; i++) {

                int rank = (int) Math.floor(Math.pow(random.nextDouble(), 3) * 400);

                text.append("w").append(rank).append(' ');
            }

            result.add(new Document(d, "title " + d, text.toString(), "path/" + d));
        }

        return result;
    }

    @Test
    void diskPostingsMatchInMemoryIndex() throws Exception {

        List<Document> docs = corpus(3000, 42);

        PositionalInvertedIndex reference = new PositionalInvertedIndex();

        for (Document doc : docs) {

            reference.addDocument(doc, PARSER.parse(doc));
        }

        Path dir = tempDir.resolve("index");

        // A tiny budget forces many segments, exercising the merge.
        try (DiskIndexWriter writer = new DiskIndexWriter(dir, PARSER, 16 * 1024)) {

            for (Document doc : docs) {

                writer.add(doc);
            }
        }

        try (DiskIndex index = DiskIndex.open(dir)) {

            assertEquals(docs.size(), index.numDocs());

            assertEquals(reference.getAverageDocumentLength(), index.averageDocLength(), 1e-9);

            Random random = new Random(7);

            for (int rank = 0; rank < 400; rank++) {

                String term = "w" + rank;

                List<PositionalPosting> expected = new ArrayList<>(reference.getPositionalPostings(term));

                PostingsIterator it = index.postings(term);

                if (expected.isEmpty()) {

                    assertNull(it, term);

                    continue;
                }

                assertNotNull(it, term);

                assertEquals(expected.size(), index.docFreq(term), term);

                // Full sequential scan with positions.
                for (PositionalPosting posting : expected) {

                    assertEquals(posting.getDocId(), it.nextDoc(), term);

                    assertEquals(posting.getTermFrequency(), it.freq(), term);

                    int[] expectedPositions = posting.getPositions().stream().mapToInt(Integer::intValue).toArray();

                    assertArrayEquals(expectedPositions, it.positions(), term);
                }

                assertEquals(PostingsIterator.NO_MORE_DOCS, it.nextDoc(), term);

                // Random advance() jumps, checking positions after skipping.
                PostingsIterator jumper = index.postings(term);

                int target = 0;

                while (true) {

                    target += 1 + random.nextInt(300);

                    int expectedDoc = firstAtLeast(expected, target);

                    int actual = jumper.advance(target);

                    assertEquals(expectedDoc, actual, term + " advance(" + target + ")");

                    if (actual == PostingsIterator.NO_MORE_DOCS) {

                        break;
                    }

                    target = actual;

                    assertArrayEquals(
                            reference.getPositions(term, actual).stream().mapToInt(Integer::intValue).toArray(),
                            jumper.positions(), term + " positions at " + actual);
                }
            }

            assertNull(index.postings("missing"));

            assertNull(index.termInfo("a"));

            assertNull(index.termInfo("zzz"));

            StoredDocument stored = index.document(1234);

            assertEquals("title 1234", stored.title());

            assertEquals("path/1234", stored.path());

            assertEquals(PARSER.parse(docs.get(1234)).size(), index.docLength(1234));
        }
    }

    @Test
    void termStatisticsSupportScoreBounds() throws Exception {

        Path dir = tempDir.resolve("stats");

        try (DiskIndexWriter writer = new DiskIndexWriter(dir, PARSER, 1 << 20)) {

            writer.add(new Document(0, "apple apple apple banana", "a"));

            writer.add(new Document(1, "apple", "b"));

            writer.add(new Document(2, "banana cherry", "c"));
        }

        try (DiskIndex index = DiskIndex.open(dir)) {

            TermInfo apple = index.termInfo("apple");

            assertEquals(2, apple.docFreq());

            assertEquals(4, apple.totalFreq());

            assertEquals(3, apple.maxFreq());

            assertEquals(1, apple.minDocLen());

            assertEquals(PostingsIterator.NO_MORE_DOCS, index.postings("cherry").advance(3));
        }
    }

    @Test
    void refusesToOverwriteExistingIndex() throws Exception {

        Path dir = tempDir.resolve("existing");

        try (DiskIndexWriter writer = new DiskIndexWriter(dir, PARSER, 1 << 20)) {

            writer.add(new Document(0, "hello", "a"));
        }

        assertThrows(java.io.IOException.class, () -> new DiskIndexWriter(dir, PARSER, 1 << 20));
    }

    private static int firstAtLeast(List<PositionalPosting> postings, int target) {

        for (PositionalPosting posting : postings) {

            if (posting.getDocId() >= target) {

                return posting.getDocId();
            }
        }

        return PostingsIterator.NO_MORE_DOCS;
    }
}
