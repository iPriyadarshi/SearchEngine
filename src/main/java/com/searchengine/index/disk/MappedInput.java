package com.searchengine.index.disk;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Cursor over a memory-mapped index file. Cursors are cheap: many can share
 * one mapped segment (one per query/iterator), and the OS page cache - not the
 * Java heap - holds the file contents. Uses the FFM API, so files larger than
 * 2 GB map in one piece.
 */
final class MappedInput {

    static final ValueLayout.OfInt INT_BE =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    static final ValueLayout.OfLong LONG_BE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final MemorySegment segment;

    private long position;

    MappedInput(MemorySegment segment, long position) {

        this.segment = segment;

        this.position = position;
    }

    static MemorySegment map(Path file, Arena arena) throws IOException {

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {

            long size = channel.size();

            if (size == 0) {

                return MemorySegment.ofArray(new byte[0]);
            }

            return channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
        }
    }

    long position() {

        return position;
    }

    void seek(long newPosition) {

        this.position = newPosition;
    }

    boolean hasRemaining() {

        return position < segment.byteSize();
    }

    int readByte() {

        return segment.get(ValueLayout.JAVA_BYTE, position++) & 0xFF;
    }

    int readInt() {

        int value = segment.get(INT_BE, position);

        position += Integer.BYTES;

        return value;
    }

    long readLong() {

        long value = segment.get(LONG_BE, position);

        position += Long.BYTES;

        return value;
    }

    int readVInt() {

        int b = readByte();

        int value = b & 0x7F;

        for (int shift = 7; (b & 0x80) != 0; shift += 7) {

            b = readByte();

            value |= (b & 0x7F) << shift;
        }

        return value;
    }

    long readVLong() {

        int b = readByte();

        long value = b & 0x7F;

        for (int shift = 7; (b & 0x80) != 0; shift += 7) {

            b = readByte();

            value |= (long) (b & 0x7F) << shift;
        }

        return value;
    }

    String readString() {

        int length = readVInt();

        byte[] bytes = new byte[length];

        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, bytes, 0, length);

        position += length;

        return new String(bytes, StandardCharsets.UTF_8);
    }
}
