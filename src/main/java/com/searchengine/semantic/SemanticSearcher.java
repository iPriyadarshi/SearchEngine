package com.searchengine.semantic;

import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopDocs.ScoredDoc;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.stream.IntStream;

/**
 * Finds the documents whose embeddings are closest to a query's: an exact
 * nearest-neighbour search (dot product with every document vector), split
 * across cores. At a few hundred thousand documents this takes tens of
 * milliseconds; an approximate index (e.g. HNSW) would be the next step for
 * tens of millions.
 */
public final class SemanticSearcher implements Closeable {

    private static final int CHUNK = 16_384;

    private static final Comparator<ScoredDoc> WORST_FIRST = Comparator.comparingDouble(ScoredDoc::score)
            .thenComparing(ScoredDoc::docId, Comparator.reverseOrder());

    private final Embedder embedder;

    private final VectorStore vectors;

    SemanticSearcher(Embedder embedder, VectorStore vectors) {

        this.embedder = embedder;

        this.vectors = vectors;
    }

    /**
     * Loads the vectors of an index and the model recorded with them, or
     * returns {@code null} if the index has no embeddings.
     */
    public static SemanticSearcher open(Path indexDir) throws IOException {

        if (!VectorStore.exists(indexDir)) {

            return null;
        }

        Properties settings = VectorStore.settings(indexDir);

        Embedder embedder = Embedder.load(Path.of(settings.getProperty("model")), 2);

        return new SemanticSearcher(embedder, VectorStore.load(indexDir));
    }

    public TopDocs search(String query, int k) throws IOException {

        if (k <= 0 || vectors.count() == 0) {

            return TopDocs.empty();
        }

        float[] q = embedder.embedQuery(query);

        int chunks = (vectors.count() + CHUNK - 1) / CHUNK;

        List<ScoredDoc> merged = IntStream.range(0, chunks).parallel()
                .mapToObj(c -> topInRange(q, c * CHUNK, Math.min(vectors.count(), (c + 1) * CHUNK), k))
                .flatMap(List::stream)
                .sorted(WORST_FIRST.reversed())
                .limit(k)
                .toList();

        return new TopDocs(merged, -1, vectors.count());
    }

    private List<ScoredDoc> topInRange(float[] q, int from, int to, int k) {

        PriorityQueue<ScoredDoc> heap = new PriorityQueue<>(Math.min(k, 1024) + 1, WORST_FIRST);

        for (int d = from; d < to; d++) {

            float score = vectors.dot(q, d);

            if (heap.size() < k || score > heap.peek().score()) {

                heap.add(new ScoredDoc(d, score));

                if (heap.size() > k) {

                    heap.poll();
                }
            }
        }

        return new ArrayList<>(heap);
    }

    @Override
    public void close() throws IOException {

        embedder.close();
    }
}
