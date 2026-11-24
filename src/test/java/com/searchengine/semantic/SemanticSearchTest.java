package com.searchengine.semantic;

import com.searchengine.Main;
import com.searchengine.document.model.Document;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.index.disk.StoredDocument;
import com.searchengine.search.DiskSearchBackend;
import com.searchengine.search.SearchBackend;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SemanticSearchTest {

    @Test
    void quantizedVectorsKeepDotProductsAccurate(@TempDir Path dir) throws Exception {

        Random random = new Random(3);

        List<float[]> vectors = new ArrayList<>();

        try (VectorStore.Writer writer = new VectorStore.Writer(dir, 50, 384)) {

            for (int i = 0; i < 50; i++) {

                float[] v = new float[384];

                for (int j = 0; j < v.length; j++) {

                    v[j] = (float) random.nextGaussian();
                }

                v = Embedder.normalize(v);

                vectors.add(v);

                writer.write(v);
            }

            Properties settings = new Properties();

            settings.setProperty("model", "unused");

            writer.commit(settings);
        }

        VectorStore store = VectorStore.load(dir);

        assertEquals(50, store.count());

        for (int i = 1; i < 50; i++) {

            double exact = EmbedderTest.dot(vectors.getFirst(), vectors.get(i));

            assertEquals(exact, store.dot(vectors.getFirst(), i), 0.01, "doc " + i);
        }

        assertEquals(1.0, store.dot(vectors.getFirst(), 0), 0.01);
    }

    @Test
    void hybridSearchFindsPagesThatShareNoWordsWithTheQuery(@TempDir Path dir) throws Exception {

        Assumptions.assumeTrue(Files.exists(EmbedderTest.MODEL.resolve("model.onnx")), "model not downloaded");

        Path idx = dir.resolve("idx");

        try (DiskIndexWriter writer = new DiskIndexWriter(idx, Main.buildParser(), 1 << 20)) {

            writer.add(new Document(0, "Cars", "", "A car is a wheeled motor vehicle used for transportation on roads.",
                    "https://example.org/cars", List.of()));

            writer.add(new Document(0, "Bananas", "", "Bananas are an elongated, edible fruit produced by flowering plants.",
                    "https://example.org/bananas", List.of()));

            writer.add(new Document(0, "Violins", "", "The violin is a wooden string instrument played with a bow.",
                    "https://example.org/violins", List.of()));
        }

        try (DiskIndex index = DiskIndex.open(idx);
             Embedder embedder = Embedder.load(EmbedderTest.MODEL, 2);
             VectorStore.Writer writer = new VectorStore.Writer(idx, index.numDocs(), embedder.dimensions())) {

            List<String> texts = new ArrayList<>();

            for (int d = 0; d < index.numDocs(); d++) {

                StoredDocument doc = index.document(d);

                texts.add(doc.title() + "\n" + doc.text());
            }

            for (float[] v : embedder.embedDocuments(texts, 128)) {

                writer.write(v);
            }

            Properties settings = new Properties();

            settings.setProperty("model", EmbedderTest.MODEL.toAbsolutePath().toString());

            writer.commit(settings);
        }

        try (DiskSearchBackend backend = new DiskSearchBackend(DiskIndex.open(idx), Main.buildParser(), idx)) {

            assertTrue(backend.semanticEnabled());

            // "automobile" appears nowhere: keyword-only rankings find nothing...
            assertTrue(backend.search("automobile", "tfidf", 10).hits().isEmpty());

            // ...but the hybrid ranking puts the page about cars first.
            SearchBackend.Hits hits = backend.search("automobile", "bm25", 10);

            assertFalse(hits.hits().isEmpty());

            assertEquals("https://example.org/cars", hits.hits().getFirst().path());

            assertEquals("https://example.org/violins",
                    backend.search("musical instrument with strings", "bm25", 1).hits().getFirst().path());
        }
    }
}
