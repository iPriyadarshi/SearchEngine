package com.searchengine.index.disk;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Buffered, position-tracking file writer with variable-length integer
 * encoding (7 bits per byte, high bit = "more bytes follow"). Small numbers,
 * which dominate delta-encoded postings, take a single byte.
 */
final class IndexOutput implements Closeable {

    private final OutputStream out;

    private long position;

    IndexOutput(Path file) throws IOException {

        this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
    }

    long position() {

        return position;
    }

    void writeByte(int b) throws IOException {

        out.write(b);

        position++;
    }

    void writeBytes(byte[] bytes, int offset, int length) throws IOException {

        out.write(bytes, offset, length);

        position += length;
    }

    void writeVInt(int value) throws IOException {

        while ((value & ~0x7F) != 0) {

            writeByte((value & 0x7F) | 0x80);

            value >>>= 7;
        }

        writeByte(value);
    }

    void writeVLong(long value) throws IOException {

        while ((value & ~0x7FL) != 0) {

            writeByte((int) ((value & 0x7F) | 0x80));

            value >>>= 7;
        }

        writeByte((int) value);
    }

    void writeInt(int value) throws IOException {

        writeByte(value >>> 24);

        writeByte(value >>> 16);

        writeByte(value >>> 8);

        writeByte(value);
    }

    void writeLong(long value) throws IOException {

        writeInt((int) (value >>> 32));

        writeInt((int) value);
    }

    void writeString(String value) throws IOException {

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        writeVInt(bytes.length);

        writeBytes(bytes, 0, bytes.length);
    }

    @Override
    public void close() throws IOException {

        out.close();
    }

    /**
     * Append a varint to a growable byte array; returns the (possibly
     * reallocated) array. {@code lengthHolder[0]} is the used length.
     */
    static byte[] appendVInt(byte[] bytes, int[] lengthHolder, int value) {

        int length = lengthHolder[0];

        if (length + 5 > bytes.length) {

            bytes = java.util.Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + 5));
        }

        while ((value & ~0x7F) != 0) {

            bytes[length++] = (byte) ((value & 0x7F) | 0x80);

            value >>>= 7;
        }

        bytes[length++] = (byte) value;

        lengthHolder[0] = length;

        return bytes;
    }
}
