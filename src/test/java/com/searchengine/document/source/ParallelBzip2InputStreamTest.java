package com.searchengine.document.source;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParallelBzip2InputStreamTest {

    @TempDir
    Path dir;

    /**
     * Builds a multistream file the way Wikipedia does: many independent
     * bzip2 streams concatenated. Returns the uncompressed bytes.
     */
    private byte[] writeMultistream(Path file, int streams) throws Exception {

        Random random = new Random(1);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();

        try (OutputStream out = Files.newOutputStream(file)) {

            for (int s = 0; s < streams; s++) {

                StringBuilder text = new StringBuilder();

                for (int i = 0; i < 2000; i++) {

                    text.append("page ").append(s).append(" word").append(random.nextInt(5000)).append('\n');
                }

                byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);

                expected.write(bytes);

                ByteArrayOutputStream compressed = new ByteArrayOutputStream();

                try (BZip2CompressorOutputStream bz = new BZip2CompressorOutputStream(compressed)) {

                    bz.write(bytes);
                }

                out.write(compressed.toByteArray());
            }
        }

        return expected.toByteArray();
    }

    @Test
    void parallelOutputIsIdenticalToSequential() throws Exception {

        Path file = dir.resolve("multi.xml.bz2");

        byte[] expected = writeMultistream(file, 60);

        assertTrue(ParallelBzip2InputStream.isMultistream(file));

        // Small chunks force many cuts at stream boundaries.
        try (InputStream in = ParallelBzip2InputStream.open(file, 4, 8 * 1024)) {

            assertArrayEquals(expected, in.readAllBytes());
        }

        try (InputStream in = ParallelBzip2InputStream.open(file, 1)) {

            assertArrayEquals(expected, in.readAllBytes());
        }
    }

    @Test
    void singleStreamFilesFallBackToSequentialDecoding() throws Exception {

        Path file = dir.resolve("single.xml.bz2");

        byte[] expected = "hello single stream".repeat(1000).getBytes(StandardCharsets.UTF_8);

        try (OutputStream out = new BZip2CompressorOutputStream(Files.newOutputStream(file))) {

            out.write(expected);
        }

        assertFalse(ParallelBzip2InputStream.isMultistream(file));

        try (InputStream in = ParallelBzip2InputStream.open(file, 4)) {

            assertArrayEquals(expected, in.readAllBytes());
        }
    }
}
