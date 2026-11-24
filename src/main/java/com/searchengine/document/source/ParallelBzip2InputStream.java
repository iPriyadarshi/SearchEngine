package com.searchengine.document.source;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Decompresses a <em>multistream</em> bzip2 file on several threads while
 * presenting the output as one ordinary, in-order {@link InputStream}.
 * <p>
 * Wikipedia's {@code *-multistream.xml.bz2} dumps are many small, independent
 * bzip2 streams concatenated together. Each stream starts on a byte boundary
 * with the header {@code "BZh"} + level digit, immediately followed by the
 * 48-bit block magic {@code 0x314159265359}. A splitter thread cuts the file
 * at those boundaries into chunks of roughly {@value #TARGET_CHUNK_BYTES}
 * bytes, a pool decompresses the chunks concurrently, and {@link #read}
 * consumes the results in file order. Because every chunk holds whole streams,
 * the concatenated output is byte-identical to sequential decompression.
 * <p>
 * bzip2 decompression is CPU heavy (it was ~half of single-threaded indexing
 * time), so this is the main lever for build throughput.
 */
public final class ParallelBzip2InputStream extends InputStream {

    static final int TARGET_CHUNK_BYTES = 4 << 20;

    // A single-stream file shows no second stream header this early on.
    private static final int PROBE_BYTES = 32 << 20;

    private static final byte[] BLOCK_MAGIC = {0x31, 0x41, 0x59, 0x26, 0x53, 0x59};

    private static final Future<byte[]> END = CompletableFuture.completedFuture(new byte[0]);

    private final ExecutorService pool;

    private final BlockingQueue<Future<byte[]>> chunks;

    private final Thread splitter;

    private final int chunkBytes;

    private byte[] current = new byte[0];

    private int position;

    private boolean finished;

    private ParallelBzip2InputStream(Path file, int threads, int chunkBytes) {

        this.chunkBytes = chunkBytes;

        this.pool = Executors.newFixedThreadPool(threads, daemon("bzip2-decoder"));

        this.chunks = new ArrayBlockingQueue<>(threads * 2);

        this.splitter = daemon("bzip2-splitter").newThread(() -> split(file));

        splitter.start();
    }

    /**
     * Open a bzip2 file, decompressing in parallel when it is multistream and
     * falling back to a sequential decoder otherwise.
     */
    public static InputStream open(Path file, int threads) throws IOException {

        return open(file, threads, TARGET_CHUNK_BYTES);
    }

    static InputStream open(Path file, int threads, int chunkBytes) throws IOException {

        if (threads > 1 && isMultistream(file)) {

            return new ParallelBzip2InputStream(file, threads, chunkBytes);
        }

        return new BZip2CompressorInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16), true);
    }

    static boolean isMultistream(Path file) throws IOException {

        try (InputStream in = Files.newInputStream(file)) {

            byte[] probe = in.readNBytes(PROBE_BYTES);

            return nextStreamStart(probe, 1, probe.length) >= 0;
        }
    }

    /**
     * Index of the first stream header in {@code [from, limit)}, or -1.
     */
    static int nextStreamStart(byte[] bytes, int from, int limit) {

        int last = limit - 4 - BLOCK_MAGIC.length;

        for (int i = Math.max(from, 0); i <= last; i++) {

            if (bytes[i] == 'B' && bytes[i + 1] == 'Z' && bytes[i + 2] == 'h'
                    && bytes[i + 3] >= '1' && bytes[i + 3] <= '9'
                    && Arrays.equals(bytes, i + 4, i + 4 + BLOCK_MAGIC.length, BLOCK_MAGIC, 0, BLOCK_MAGIC.length)) {

                return i;
            }
        }

        return -1;
    }

    private void split(Path file) {

        try (InputStream in = Files.newInputStream(file)) {

            byte[] buffer = new byte[chunkBytes * 2];

            int length = 0;

            int scanFrom = chunkBytes;

            boolean eof = false;

            while (!eof) {

                if (length == buffer.length) {

                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }

                int read = in.read(buffer, length, buffer.length - length);

                if (read < 0) {

                    eof = true;

                } else {

                    length += read;
                }

                // Cut at the first stream boundary past the target size.
                while (length > scanFrom) {

                    int cut = nextStreamStart(buffer, scanFrom, length);

                    if (cut < 0) {

                        // Keep the tail that might hold a partial header.
                        scanFrom = Math.max(scanFrom, length - 4 - BLOCK_MAGIC.length + 1);

                        break;
                    }

                    submit(Arrays.copyOf(buffer, cut));

                    System.arraycopy(buffer, cut, buffer, 0, length - cut);

                    length -= cut;

                    scanFrom = chunkBytes;
                }
            }

            if (length > 0) {

                submit(Arrays.copyOf(buffer, length));
            }

            chunks.put(END);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        } catch (IOException | RuntimeException e) {

            chunks.offer(CompletableFuture.failedFuture(e));
        }
    }

    private void submit(byte[] compressed) throws InterruptedException {

        chunks.put(pool.submit(() -> {

            try (InputStream in = new BZip2CompressorInputStream(new ByteArrayInputStream(compressed), true)) {

                return in.readAllBytes();
            }
        }));
    }

    private boolean fill() throws IOException {

        while (position >= current.length) {

            if (finished) {

                return false;
            }

            try {

                Future<byte[]> next = chunks.take();

                if (next == END) {

                    finished = true;

                    return false;
                }

                current = next.get();

                position = 0;

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                throw new InterruptedIOException("Interrupted while decompressing");

            } catch (ExecutionException e) {

                throw new IOException("bzip2 decompression failed", e.getCause());
            }
        }

        return true;
    }

    @Override
    public int read() throws IOException {

        return fill() ? current[position++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {

        if (len == 0) {

            return 0;
        }

        if (!fill()) {

            return -1;
        }

        int n = Math.min(len, current.length - position);

        System.arraycopy(current, position, b, off, n);

        position += n;

        return n;
    }

    @Override
    public void close() {

        splitter.interrupt();

        pool.shutdownNow();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {

        return runnable -> {

            Thread thread = new Thread(runnable, name);

            thread.setDaemon(true);

            return thread;
        };
    }
}
