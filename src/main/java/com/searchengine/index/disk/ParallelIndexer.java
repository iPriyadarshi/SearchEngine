package com.searchengine.index.disk;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Three-stage indexing pipeline:
 * <pre>
 * reader thread ──batches──▶ worker pool (prepare + analyze) ──ordered futures──▶ calling thread (DiskIndexWriter)
 * </pre>
 * Reading the source (XML parsing) and writing the index are inherently
 * sequential, but turning raw items into analyzed documents - markup
 * cleaning, tokenizing, stemming - is independent per document and is spread
 * over the pool. Futures are consumed in submission order, so doc ids are
 * deterministic and identical to a single-threaded build. The queue is
 * bounded, so memory stays flat however far ahead the reader could run.
 */
public final class ParallelIndexer {

    private static final int BATCH_SIZE = 64;

    private ParallelIndexer() {
    }

    private record Analyzed(Document doc, List<String> tokens) {
    }

    /**
     * @param items   raw items in source order (consumed on a reader thread)
     * @param prepare converts an item into a document, or {@code null} to skip it; must be thread-safe
     * @param parser  analysis chain; must be thread-safe
     * @param maxDocs stop after this many indexed documents
     * @return number of documents added
     */
    public static <T> int index(Iterator<T> items,
                                Function<T, Document> prepare,
                                Parser parser,
                                DiskIndexWriter writer,
                                int threads,
                                long maxDocs) throws IOException {

        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {

            Thread thread = new Thread(runnable, "index-worker");

            thread.setDaemon(true);

            return thread;
        });

        BlockingQueue<Future<List<Analyzed>>> queue = new ArrayBlockingQueue<>(threads * 4);

        Future<List<Analyzed>> end = CompletableFuture.completedFuture(List.of());

        Thread reader = new Thread(() -> {

            try {

                List<T> batch = new ArrayList<>(BATCH_SIZE);

                while (items.hasNext()) {

                    batch.add(items.next());

                    if (batch.size() == BATCH_SIZE) {

                        queue.put(submit(pool, batch, prepare, parser));

                        batch = new ArrayList<>(BATCH_SIZE);
                    }
                }

                if (!batch.isEmpty()) {

                    queue.put(submit(pool, batch, prepare, parser));
                }

                queue.put(end);

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

            } catch (RuntimeException e) {

                // Blocking put: a dropped failure would leave the consumer waiting forever.
                try {

                    queue.put(CompletableFuture.failedFuture(e));

                } catch (InterruptedException interrupted) {

                    Thread.currentThread().interrupt();
                }
            }
        }, "index-reader");

        reader.setDaemon(true);

        reader.start();

        int added = 0;

        try {

            while (added < maxDocs) {

                Future<List<Analyzed>> next = queue.take();

                if (next == end) {

                    break;
                }

                for (Analyzed analyzed : next.get()) {

                    if (added >= maxDocs) {

                        break;
                    }

                    writer.add(analyzed.doc(), analyzed.tokens());

                    added++;
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Indexing interrupted");

        } catch (ExecutionException e) {

            Throwable cause = e.getCause();

            if (cause instanceof java.io.UncheckedIOException unchecked) {

                throw unchecked.getCause();
            }

            throw new IOException("Indexing failed", cause);

        } finally {

            reader.interrupt();

            pool.shutdownNow();
        }

        return added;
    }

    private static <T> Future<List<Analyzed>> submit(ExecutorService pool,
                                                     List<T> batch,
                                                     Function<T, Document> prepare,
                                                     Parser parser) {

        return pool.submit(() -> {

            List<Analyzed> result = new ArrayList<>(batch.size());

            for (T item : batch) {

                Document doc = prepare.apply(item);

                if (doc != null) {

                    result.add(new Analyzed(doc, parser.parse(doc)));
                }
            }

            return result;
        });
    }
}
