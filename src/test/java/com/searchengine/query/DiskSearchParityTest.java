package com.searchengine.query;

import com.searchengine.api.Parser;
import com.searchengine.api.Ranker;
import com.searchengine.document.model.Document;
import com.searchengine.engine.SearchEngine;
import com.searchengine.index.core.PositionalInvertedIndex;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.parser.core.DefaultParser;
import com.searchengine.parser.filter.LowercaseFilter;
import com.searchengine.parser.tokenizer.RegexTokenizer;
import com.searchengine.query.disk.BooleanSearcher;
import com.searchengine.query.disk.PhraseSearcher;
import com.searchengine.query.disk.Similarity;
import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopKSearcher;
import com.searchengine.query.executor.BooleanQueryExecutor;
import com.searchengine.query.executor.PhraseQueryExecutor;
import com.searchengine.query.model.PhraseQuery;
import com.searchengine.query.parser.BooleanQueryParser;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.ranking.core.BM25Ranker;
import com.searchengine.ranking.core.CosineSimilarityRanker;
import com.searchengine.ranking.core.TFIDFRanker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disk searchers must return exactly what the in-memory executors return
 * on the same corpus.
 */
public class DiskSearchParityTest {

    private static final Parser PARSER = new DefaultParser(new RegexTokenizer(), List.of(new LowercaseFilter()));

    private static final int VOCABULARY = 300;

    @TempDir
    Path dir;

    private SearchEngine memory;

    private PositionalInvertedIndex memoryIndex;

    private DiskIndex disk;

    @BeforeEach
    void build() throws Exception {

        Random random = new Random(11);

        memoryIndex = new PositionalInvertedIndex();

        memory = new SearchEngine(PARSER, memoryIndex);

        try (DiskIndexWriter writer = new DiskIndexWriter(dir.resolve("idx"), PARSER, 64 * 1024)) {

            for (int d = 0; d < 4000; d++) {

                StringBuilder text = new StringBuilder();

                for (int i = 0, n = 1 + random.nextInt(50); i < n; i++) {

                    // Skewed: low ranks are very common, so MaxScore has terms to prune.
                    text.append('w').append((int) (Math.pow(random.nextDouble(), 2.5) * VOCABULARY)).append(' ');
                }

                Document doc = new Document(d, text.toString(), "p" + d);

                memory.indexDocument(doc);

                writer.add(doc);
            }
        }

        disk = DiskIndex.open(dir.resolve("idx"));
    }

    @AfterEach
    void close() {

        disk.close();
    }

    private List<String> randomQuery(Random random) {

        List<String> terms = new ArrayList<>();

        for (int i = 0, n = 1 + random.nextInt(5); i < n; i++) {

            terms.add("w" + random.nextInt(VOCABULARY));
        }

        return terms;
    }

    @Test
    void rankedTopKMatchesFullScoring() {

        Map<Ranker, Similarity> pairs = Map.of(
                new TFIDFRanker(memoryIndex), Similarity.tfIdf(),
                new CosineSimilarityRanker(memoryIndex), Similarity.cosine(),
                new BM25Ranker(memoryIndex), Similarity.bm25());

        TopKSearcher searcher = new TopKSearcher(disk);

        Random random = new Random(5);

        long scored = 0;

        long matched = 0;

        for (int q = 0; q < 300; q++) {

            List<String> terms = randomQuery(random);

            for (Map.Entry<Ranker, Similarity> pair : pairs.entrySet()) {

                List<RankedDocument> expected = pair.getKey().rank(terms);

                int k = 1 + random.nextInt(20);

                TopDocs actual = searcher.search(terms, pair.getValue(), k);

                assertEquals(Math.min(k, expected.size()), actual.hits().size(), terms.toString());

                for (int i = 0; i < actual.hits().size(); i++) {

                    // Summation order differs, so compare scores with a tolerance.
                    assertEquals(expected.get(i).getScore(), actual.hits().get(i).score(), 1e-9,
                            terms + " rank " + i);
                }

                scored += actual.scored();

                matched += expected.size();
            }
        }

        // MaxScore must have skipped a meaningful share of candidates.
        assertTrue(scored < matched, "scored " + scored + " of " + matched);
    }

    @Test
    void phraseMatchesInMemoryExecutor() {

        PhraseQueryExecutor executor = new PhraseQueryExecutor(memoryIndex);

        PhraseSearcher searcher = new PhraseSearcher(disk);

        Random random = new Random(9);

        for (int q = 0; q < 300; q++) {

            // Common terms, so multi-word phrases actually occur.
            List<String> terms = new ArrayList<>();

            for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) {

                terms.add("w" + random.nextInt(8));
            }

            List<RankedDocument> expected = executor.execute(new PhraseQuery(terms)).getResults();

            TopDocs actual = searcher.search(terms, 1_000_000);

            assertEquals(expected.size(), actual.totalHits(), terms.toString());

            for (int i = 0; i < expected.size(); i++) {

                assertEquals(expected.get(i).getScore(), actual.hits().get(i).score(), terms + " #" + i);
            }
        }
    }

    @Test
    void booleanMatchesInMemoryExecutor() {

        BooleanQueryExecutor executor = new BooleanQueryExecutor(memoryIndex, memoryIndex, memory::analyzeQuery);

        BooleanSearcher searcher = new BooleanSearcher(disk, memory::analyzeQuery);

        BooleanQueryParser parser = new BooleanQueryParser();

        List<String> queries = List.of(
                "w1 AND w2",
                "w1 OR w250",
                "NOT w0",
                "w3 AND NOT w4",
                "(w1 OR w2) AND (w3 OR NOT w5)",
                "w299 AND w298",
                "missing OR w7",
                "missing AND w7",
                "NOT (w0 OR w1 OR w2)");

        for (String query : queries) {

            List<RankedDocument> expected = executor.execute(parser.parse(query)).getResults();

            TopDocs actual = searcher.search(parser.parse(query), Integer.MAX_VALUE);

            assertEquals(expected.size(), actual.totalHits(), query);

            for (int i = 0; i < expected.size(); i++) {

                assertEquals(expected.get(i).getDocId(), actual.hits().get(i).docId(), query + " #" + i);
            }
        }
    }
}
