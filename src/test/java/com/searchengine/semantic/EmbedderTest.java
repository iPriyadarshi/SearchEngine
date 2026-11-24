package com.searchengine.semantic;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmbedderTest {

    static final Path MODEL = Path.of("data/models/bge-small-en-v1.5");

    @Test
    void basicTokenizationLowercasesStripsAccentsAndSplitsPunctuation() {

        assertEquals(List.of("hello", ",", "cafe", "world", "!", "it", "'", "s"),
                WordPieceTokenizer.basicTokens("Hello, Café\tWORLD! It's"));

        assertEquals(List.of("东", "京", "tokyo"), WordPieceTokenizer.basicTokens("东京Tokyo"));
    }

    @Test
    void wordPieceUsesLongestPiecesAndUnknown() {

        Map<String, Integer> vocab = new HashMap<>();

        String[] tokens = {"[PAD]", "[UNK]", "[CLS]", "[SEP]", "un", "##aff", "##able", "aff", "able", "run", "##ning"};

        for (int i = 0; i < tokens.length; i++) {

            vocab.put(tokens[i], i);
        }

        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocab);

        assertArrayEquals(new int[]{2, 4, 5, 6, 9, 10, 3}, tokenizer.encode("unaffable running", 32));

        assertArrayEquals(new int[]{2, 1, 3}, tokenizer.encode("xyz", 32));

        // Truncation keeps [CLS] and [SEP].
        assertArrayEquals(new int[]{2, 4, 3}, tokenizer.encode("unaffable", 3));
    }

    @Test
    void realVocabularyMatchesBertIds() throws Exception {

        Assumptions.assumeTrue(Files.exists(MODEL.resolve("vocab.txt")), "model not downloaded");

        WordPieceTokenizer tokenizer = WordPieceTokenizer.load(MODEL.resolve("vocab.txt"));

        // bert-base-uncased: [CLS]=101 hello=7592 world=2088 [SEP]=102
        assertArrayEquals(new int[]{101, 7592, 2088, 102}, tokenizer.encode("Hello world", 512));
    }

    @Test
    void similarMeaningsGetCloserVectors() throws Exception {

        Assumptions.assumeTrue(Files.exists(MODEL.resolve("model.onnx")), "model not downloaded");

        try (Embedder embedder = Embedder.load(MODEL, 2)) {

            assertEquals(384, embedder.dimensions());

            float[] query = embedder.embedQuery("how tall is the eiffel tower");

            float[][] docs = embedder.embedDocuments(List.of(
                    "The Eiffel Tower is a wrought-iron lattice tower in Paris. It is 330 metres high.",
                    "Photosynthesis is the process plants use to turn light into chemical energy."), 128);

            double norm = 0;

            for (float x : query) {

                norm += x * x;
            }

            assertEquals(1.0, norm, 1e-4);

            assertTrue(dot(query, docs[0]) > dot(query, docs[1]) + 0.1,
                    "eiffel " + dot(query, docs[0]) + " vs photosynthesis " + dot(query, docs[1]));
        }
    }

    static double dot(float[] a, float[] b) {

        double sum = 0;

        for (int i = 0; i < a.length; i++) {

            sum += a[i] * b[i];
        }

        return sum;
    }
}
