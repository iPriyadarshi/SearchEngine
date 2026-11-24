package com.searchengine.semantic;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Document embeddings stored next to an index ({@value #FILE}).
 * <p>
 * Each unit vector is quantized to signed bytes with its own scale (its
 * largest absolute component maps to 127): a quarter of the size of floats,
 * with dot products accurate to well under 1%. The model that produced the
 * vectors is recorded in {@value #SETTINGS}, so queries are embedded the same
 * way.
 * <pre>
 * vectors.bin: int magic, int count, int dimensions,
 *              then per document: float scale, dimensions x byte
 * </pre>
 */
public final class VectorStore {

    public static final String FILE = "vectors.bin";

    public static final String SETTINGS = "embedding.properties";

    private static final int MAGIC = 0x53465645; // "SFVE"

    private final byte[] values;

    private final float[] scales;

    private final int dimensions;

    private VectorStore(byte[] values, float[] scales, int dimensions) {

        this.values = values;

        this.scales = scales;

        this.dimensions = dimensions;
    }

    public static boolean exists(Path indexDir) {

        return Files.exists(indexDir.resolve(FILE)) && Files.exists(indexDir.resolve(SETTINGS));
    }

    public static VectorStore load(Path indexDir) throws IOException {

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexDir.resolve(FILE)), 1 << 16))) {

            if (in.readInt() != MAGIC) {

                throw new IOException("Not a vector file in " + indexDir);
            }

            int count = in.readInt();

            int dimensions = in.readInt();

            byte[] values = new byte[Math.multiplyExact(count, dimensions)];

            float[] scales = new float[count];

            for (int d = 0; d < count; d++) {

                scales[d] = in.readFloat();

                in.readFully(values, d * dimensions, dimensions);
            }

            return new VectorStore(values, scales, dimensions);
        }
    }

    public static Properties settings(Path indexDir) throws IOException {

        Properties settings = new Properties();

        try (InputStream in = Files.newInputStream(indexDir.resolve(SETTINGS))) {

            settings.load(in);
        }

        return settings;
    }

    public int count() {

        return scales.length;
    }

    public int dimensions() {

        return dimensions;
    }

    /**
     * Dot product of a float query vector with document {@code doc}.
     */
    public float dot(float[] query, int doc) {

        int base = doc * dimensions;

        float sum = 0;

        for (int i = 0; i < dimensions; i++) {

            sum += query[i] * values[base + i];
        }

        return sum * scales[doc] / 127f;
    }

    /**
     * Writes vectors in document order; {@link #commit} moves the file into place.
     */
    public static final class Writer implements Closeable {

        private final Path dir;

        private final Path temp;

        private final DataOutputStream out;

        private final int dimensions;

        private boolean committed;

        public Writer(Path indexDir, int count, int dimensions) throws IOException {

            this.dir = indexDir;

            this.temp = indexDir.resolve(FILE + ".part");

            this.dimensions = dimensions;

            this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16));

            out.writeInt(MAGIC);

            out.writeInt(count);

            out.writeInt(dimensions);
        }

        public void write(float[] vector) throws IOException {

            if (vector.length != dimensions) {

                throw new IllegalArgumentException("Expected " + dimensions + " dimensions, got " + vector.length);
            }

            float max = 0;

            for (float v : vector) {

                max = Math.max(max, Math.abs(v));
            }

            float scale = max == 0 ? 1 : max;

            out.writeFloat(scale);

            for (float v : vector) {

                out.writeByte(Math.round(v / scale * 127f));
            }
        }

        public void commit(Properties settings) throws IOException {

            out.close();

            Files.move(temp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);

            try (OutputStream o = Files.newOutputStream(dir.resolve(SETTINGS))) {

                settings.store(o, "semantic search settings");
            }

            committed = true;
        }

        @Override
        public void close() throws IOException {

            if (!committed) {

                out.close();

                Files.deleteIfExists(temp);
            }
        }
    }
}
