package com.searchengine.index.disk;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Builds an on-disk index for corpora far larger than the heap, using
 * single-pass in-memory indexing (SPIMI) followed by a k-way merge.
 * <ol>
 * <li><b>Accumulate:</b> each added document is analyzed and its postings are
 * appended, already varint-encoded, to a per-term byte buffer.</li>
 * <li><b>Flush:</b> when the buffers exceed the memory budget they are written
 * to a temporary segment file with terms in sorted order, and memory is
 * released. Segments cover disjoint, increasing doc-id ranges.</li>
 * <li><b>Merge:</b> on {@link #close()} all segments are merged term by term
 * (a priority queue over the segments' current terms) into the final
 * block-encoded postings and term dictionary.</li>
 * </ol>
 * Document ids are assigned sequentially in the order documents are added;
 * the source document's own id is ignored.
 */
public class DiskIndexWriter implements Closeable {

    /**
     * Longer tokens are almost always junk (base64, URLs) and are not indexed,
     * though they still occupy a position.
     */
    static final int MAX_TERM_LENGTH = 64;

    private static final int SNIPPET_CHARS = 600;

    // Rough heap cost of a new buffered term: map entry, key String, buffer object.
    private static final int TERM_OVERHEAD_BYTES = 160;

    private static final int PROGRESS_INTERVAL = 10_000;

    private final Path dir;

    private final Path tempDir;

    private final Parser parser;

    private final long memoryBudget;

    private final Consumer<String> log;

    private final Map<String, TermBuffer> buffers = new HashMap<>();

    private final Map<String, IntList> docPositions = new HashMap<>();

    private final List<Path> segments = new ArrayList<>();

    private final IntList docLengths = new IntList();

    private final IndexOutput docsData;

    private final IndexOutput docsIndex;

    private long bufferedBytes;

    private long totalTokens;

    private final long startNanos = System.nanoTime();

    private boolean closed;

    /**
     * @param dir          output directory (created if missing; must not contain an index)
     * @param memoryBudget approximate heap bytes to spend on buffered postings before flushing a segment
     * @param log          receives progress messages
     */
    public DiskIndexWriter(Path dir, Parser parser, long memoryBudget, Consumer<String> log) throws IOException {

        this.dir = dir;

        this.tempDir = dir.resolve(IndexFiles.TEMP_DIR);

        this.parser = parser;

        this.memoryBudget = memoryBudget;

        this.log = log;

        if (Files.exists(dir.resolve(IndexFiles.META))) {

            throw new IOException("An index already exists in " + dir);
        }

        Files.createDirectories(tempDir);

        this.docsData = new IndexOutput(dir.resolve(IndexFiles.DOCS_DATA));

        this.docsIndex = new IndexOutput(dir.resolve(IndexFiles.DOCS_INDEX));
    }

    public DiskIndexWriter(Path dir, Parser parser, long memoryBudget) throws IOException {

        this(dir, parser, memoryBudget, message -> { });
    }

    /**
     * Analyze and buffer one document; returns the doc id assigned to it.
     */
    public int add(Document doc) throws IOException {

        if (closed) {

            throw new IllegalStateException("Writer is closed");
        }

        int docId = docLengths.size();

        List<String> tokens = parser.parse(doc);

        docLengths.add(tokens.size());

        totalTokens += tokens.size();

        storeDocument(doc);

        docPositions.clear();

        for (int position = 0; position < tokens.size(); position++) {

            String token = tokens.get(position);

            if (!token.isEmpty() && token.length() <= MAX_TERM_LENGTH) {

                docPositions.computeIfAbsent(token, k -> new IntList()).add(position);
            }
        }

        for (Map.Entry<String, IntList> entry : docPositions.entrySet()) {

            TermBuffer buffer = buffers.get(entry.getKey());

            if (buffer == null) {

                buffer = new TermBuffer();

                buffers.put(entry.getKey(), buffer);

                bufferedBytes += TERM_OVERHEAD_BYTES + 2L * entry.getKey().length();
            }

            bufferedBytes += buffer.addDoc(docId, entry.getValue());
        }

        if (bufferedBytes >= memoryBudget) {

            flushSegment();
        }

        if ((docId + 1) % PROGRESS_INTERVAL == 0) {

            double seconds = (System.nanoTime() - startNanos) / 1e9;

            log.accept(String.format("  indexed %,d docs (%,.0f docs/s), %d segment(s) flushed",
                    docId + 1, (docId + 1) / seconds, segments.size()));
        }

        return docId;
    }

    public int documentCount() {

        return docLengths.size();
    }

    private void storeDocument(Document doc) throws IOException {

        docsIndex.writeLong(docsData.position());

        docsData.writeString(doc.getTitle());

        docsData.writeString(doc.getPath() == null ? "" : doc.getPath());

        docsData.writeString(snippetText(doc));
    }

    private static String snippetText(Document doc) {

        String content = doc.getContent() == null ? "" : doc.getContent();

        String title = doc.getTitle();

        // Avoid repeating the title when the content starts with it.
        if (!title.isEmpty() && content.startsWith(title)) {

            content = content.substring(title.length());
        }

        String flat = content.length() > SNIPPET_CHARS * 4
                ? content.substring(0, SNIPPET_CHARS * 4)
                : content;

        flat = flat.replaceAll("\\s+", " ").strip();

        return flat.length() > SNIPPET_CHARS ? flat.substring(0, SNIPPET_CHARS) : flat;
    }

    /**
     * Write the buffered postings, sorted by term, to a temporary segment.
     * Segment format: int termCount, then per term: string term, vint docFreq,
     * vint byteLength, bytes (docDelta, freq, positionDeltas... per doc).
     */
    private void flushSegment() throws IOException {

        if (buffers.isEmpty()) {

            return;
        }

        Path segment = tempDir.resolve(String.format("seg-%05d.tmp", segments.size()));

        String[] terms = buffers.keySet().toArray(new String[0]);

        Arrays.sort(terms);

        try (IndexOutput out = new IndexOutput(segment)) {

            out.writeInt(terms.length);

            for (String term : terms) {

                TermBuffer buffer = buffers.get(term);

                out.writeString(term);

                out.writeVInt(buffer.docFreq);

                out.writeVInt(buffer.length[0]);

                out.writeBytes(buffer.bytes, 0, buffer.length[0]);
            }
        }

        segments.add(segment);

        log.accept(String.format("  flushed segment %d: %,d terms, ~%,d MB buffered",
                segments.size(), terms.length, bufferedBytes >> 20));

        buffers.clear();

        bufferedBytes = 0;
    }

    /**
     * Flush the last segment, merge everything into the final index files,
     * and remove the temporary segments.
     */
    @Override
    public void close() throws IOException {

        if (closed) {

            return;
        }

        closed = true;

        flushSegment();

        docsData.close();

        docsIndex.close();

        int[] lengths = docLengths.toArray();

        writeDocLengths(lengths);

        log.accept(String.format("  merging %d segment(s)...", segments.size()));

        long termCount = merge(lengths);

        writeMeta(lengths.length, termCount);

        for (Path segment : segments) {

            Files.deleteIfExists(segment);
        }

        Files.deleteIfExists(tempDir);

        log.accept(String.format("  done: %,d docs, %,d terms, %,d tokens in %.1fs",
                lengths.length, termCount, totalTokens, (System.nanoTime() - startNanos) / 1e9));
    }

    private void writeDocLengths(int[] lengths) throws IOException {

        try (IndexOutput out = new IndexOutput(dir.resolve(IndexFiles.DOC_LENGTHS))) {

            out.writeInt(lengths.length);

            for (int length : lengths) {

                out.writeInt(length);
            }
        }
    }

    private void writeMeta(int numDocs, long termCount) throws IOException {

        Properties meta = new Properties();

        meta.setProperty("formatVersion", String.valueOf(IndexFiles.FORMAT_VERSION));

        meta.setProperty("numDocs", String.valueOf(numDocs));

        meta.setProperty("totalTokens", String.valueOf(totalTokens));

        meta.setProperty("numTerms", String.valueOf(termCount));

        meta.setProperty("blockSize", String.valueOf(IndexFiles.BLOCK_SIZE));

        try (OutputStream out = Files.newOutputStream(dir.resolve(IndexFiles.META))) {

            meta.store(out, "search engine disk index");
        }
    }

    /**
     * K-way merge of all segments into postings.doc/.pos and the dictionary.
     * Segments hold increasing doc-id ranges, so concatenating a term's
     * postings in segment order keeps doc ids sorted.
     */
    private long merge(int[] lengths) throws IOException {

        PriorityQueue<SegmentReader> queue = new PriorityQueue<>(
                Comparator.comparing((SegmentReader r) -> r.term).thenComparingInt(r -> r.order));

        List<SegmentReader> readers = new ArrayList<>();

        long termCount = 0;

        try (PostingsWriter postings = new PostingsWriter(dir, lengths);
             IndexOutput dict = new IndexOutput(dir.resolve(IndexFiles.TERMS_DICT))) {

            for (int i = 0; i < segments.size(); i++) {

                SegmentReader reader = new SegmentReader(segments.get(i), i);

                readers.add(reader);

                if (reader.next()) {

                    queue.add(reader);
                }
            }

            List<String> sparseTerms = new ArrayList<>();

            List<Long> sparseOffsets = new ArrayList<>();

            List<SegmentReader> sameTerm = new ArrayList<>();

            int[] positions = new int[16];

            while (!queue.isEmpty()) {

                String term = queue.peek().term;

                sameTerm.clear();

                while (!queue.isEmpty() && queue.peek().term.equals(term)) {

                    sameTerm.add(queue.poll());
                }

                postings.startTerm(term);

                for (SegmentReader reader : sameTerm) {

                    positions = reader.writeTo(postings, positions);

                    if (reader.next()) {

                        queue.add(reader);
                    }
                }

                TermInfo info = postings.finishTerm();

                if (termCount % IndexFiles.DICT_INDEX_INTERVAL == 0) {

                    sparseTerms.add(term);

                    sparseOffsets.add(dict.position());
                }

                info.write(dict);

                termCount++;
            }

            try (IndexOutput index = new IndexOutput(dir.resolve(IndexFiles.TERMS_INDEX))) {

                index.writeInt(sparseTerms.size());

                for (int i = 0; i < sparseTerms.size(); i++) {

                    index.writeString(sparseTerms.get(i));

                    index.writeLong(sparseOffsets.get(i));
                }
            }

        } finally {

            for (SegmentReader reader : readers) {

                reader.close();
            }
        }

        return termCount;
    }

    /**
     * Buffered postings for one term in the current segment, varint-encoded:
     * per doc: docDelta, freq, then freq position deltas.
     */
    private static final class TermBuffer {

        private byte[] bytes = new byte[16];

        private final int[] length = {0};

        private int lastDoc = -1;

        private int docFreq;

        /**
         * Returns the number of bytes by which the buffer's heap use grew.
         */
        long addDoc(int docId, IntList positions) {

            int capacityBefore = bytes.length;

            bytes = IndexOutput.appendVInt(bytes, length, docId - lastDoc);

            bytes = IndexOutput.appendVInt(bytes, length, positions.size());

            int previous = 0;

            for (int i = 0; i < positions.size(); i++) {

                bytes = IndexOutput.appendVInt(bytes, length, positions.get(i) - previous);

                previous = positions.get(i);
            }

            lastDoc = docId;

            docFreq++;

            return bytes.length - capacityBefore;
        }
    }

    /**
     * Sequential reader over one temporary segment during the merge.
     */
    private static final class SegmentReader implements Closeable {

        private final DataInputStream in;

        private final int order;

        private int remainingTerms;

        private String term;

        private int docFreq;

        private byte[] bytes = new byte[64];

        private int length;

        SegmentReader(Path file, int order) throws IOException {

            this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 18));

            this.order = order;

            this.remainingTerms = in.readInt();
        }

        boolean next() throws IOException {

            if (remainingTerms == 0) {

                return false;
            }

            remainingTerms--;

            int termLength = readVInt();

            byte[] termBytes = new byte[termLength];

            in.readFully(termBytes);

            term = new String(termBytes, StandardCharsets.UTF_8);

            docFreq = readVInt();

            length = readVInt();

            if (length > bytes.length) {

                bytes = new byte[Math.max(length, bytes.length * 2)];
            }

            in.readFully(bytes, 0, length);

            return true;
        }

        /**
         * Decode this segment's postings for the current term and feed them to
         * the final writer. Returns the (possibly grown) positions scratch array.
         */
        int[] writeTo(PostingsWriter writer, int[] positions) throws IOException {

            int[] cursor = {0};

            int doc = -1;

            for (int d = 0; d < docFreq; d++) {

                doc += decodeVInt(cursor);

                int freq = decodeVInt(cursor);

                if (freq > positions.length) {

                    positions = new int[Math.max(freq, positions.length * 2)];
                }

                int position = 0;

                for (int i = 0; i < freq; i++) {

                    position += decodeVInt(cursor);

                    positions[i] = position;
                }

                writer.addDoc(doc, freq, positions);
            }

            return positions;
        }

        private int decodeVInt(int[] cursor) {

            int b = bytes[cursor[0]++] & 0xFF;

            int value = b & 0x7F;

            for (int shift = 7; (b & 0x80) != 0; shift += 7) {

                b = bytes[cursor[0]++] & 0xFF;

                value |= (b & 0x7F) << shift;
            }

            return value;
        }

        private int readVInt() throws IOException {

            int b = in.readUnsignedByte();

            int value = b & 0x7F;

            for (int shift = 7; (b & 0x80) != 0; shift += 7) {

                b = in.readUnsignedByte();

                value |= (b & 0x7F) << shift;
            }

            return value;
        }

        @Override
        public void close() throws IOException {

            in.close();
        }
    }

    /**
     * Minimal growable int array (avoids boxing millions of Integers).
     */
    static final class IntList {

        private int[] values = new int[4];

        private int size;

        void add(int value) {

            if (size == values.length) {

                values = Arrays.copyOf(values, size * 2);
            }

            values[size++] = value;
        }

        int get(int index) {

            return values[index];
        }

        int size() {

            return size;
        }

        int[] toArray() {

            return Arrays.copyOf(values, size);
        }
    }
}
