package com.searchengine.index.disk;

import com.searchengine.api.PostingsIterator;

import java.io.Closeable;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only view of an index built by {@link DiskIndexWriter}.
 * <p>
 * Postings, the dictionary and stored documents stay on disk and are
 * memory-mapped; only a sparse sample of the dictionary (every
 * {@value IndexFiles#DICT_INDEX_INTERVAL}th term) and the document-length
 * array are loaded onto the heap. Opening is therefore fast and the heap
 * footprint is a small fraction of the index size.
 * <p>
 * Instances are safe for concurrent queries: every {@link #postings} call
 * returns an independent iterator.
 */
public class DiskIndex implements Closeable {

    private final Arena arena = Arena.ofShared();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final MemorySegment dict;

    private final MemorySegment docs;

    private final MemorySegment positions;

    private final MemorySegment docsData;

    private final MemorySegment docsIndex;

    private final String[] sparseTerms;

    private final long[] sparseOffsets;

    private final int[] docLengths;

    private final int numDocs;

    private final long totalTokens;

    private final long numTerms;

    private DiskIndex(Path dir) throws IOException {

        Properties meta = new Properties();

        try (InputStream in = Files.newInputStream(dir.resolve(IndexFiles.META))) {

            meta.load(in);
        }

        int version = Integer.parseInt(meta.getProperty("formatVersion", "0"));

        if (version != IndexFiles.FORMAT_VERSION) {

            throw new IOException("Unsupported index format " + version + " in " + dir);
        }

        numDocs = Integer.parseInt(meta.getProperty("numDocs"));

        totalTokens = Long.parseLong(meta.getProperty("totalTokens"));

        numTerms = Long.parseLong(meta.getProperty("numTerms"));

        try {

            dict = MappedInput.map(dir.resolve(IndexFiles.TERMS_DICT), arena);

            docs = MappedInput.map(dir.resolve(IndexFiles.POSTINGS_DOC), arena);

            positions = MappedInput.map(dir.resolve(IndexFiles.POSTINGS_POS), arena);

            docsData = MappedInput.map(dir.resolve(IndexFiles.DOCS_DATA), arena);

            docsIndex = MappedInput.map(dir.resolve(IndexFiles.DOCS_INDEX), arena);

            MappedInput sparse = new MappedInput(MappedInput.map(dir.resolve(IndexFiles.TERMS_INDEX), arena), 0);

            int count = sparse.readInt();

            sparseTerms = new String[count];

            sparseOffsets = new long[count];

            for (int i = 0; i < count; i++) {

                sparseTerms[i] = sparse.readString();

                sparseOffsets[i] = sparse.readLong();
            }

            docLengths = readDocLengths(dir.resolve(IndexFiles.DOC_LENGTHS));

        } catch (IOException | RuntimeException e) {

            arena.close();

            throw e;
        }
    }

    public static DiskIndex open(Path dir) throws IOException {

        return new DiskIndex(dir);
    }

    private static int[] readDocLengths(Path file) throws IOException {

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {

            int[] lengths = new int[in.readInt()];

            for (int i = 0; i < lengths.length; i++) {

                lengths[i] = in.readInt();
            }

            return lengths;
        }
    }

    /**
     * Dictionary lookup: binary search the in-memory sample, then scan at most
     * one interval of the on-disk dictionary.
     *
     * @return the term's entry, or {@code null} if the term is not indexed
     */
    public TermInfo termInfo(String term) {

        int slot = Arrays.binarySearch(sparseTerms, term);

        if (slot < 0) {

            slot = -slot - 2;

            if (slot < 0) {

                return null;
            }
        }

        MappedInput in = new MappedInput(dict, sparseOffsets[slot]);

        for (int i = 0; i < IndexFiles.DICT_INDEX_INTERVAL && in.hasRemaining(); i++) {

            TermInfo info = TermInfo.read(in);

            int cmp = info.term().compareTo(term);

            if (cmp == 0) {

                return info;
            }

            if (cmp > 0) {

                return null;
            }
        }

        return null;
    }

    /**
     * Postings for a term, or {@code null} if the term is not indexed.
     */
    public PostingsIterator postings(String term) {

        TermInfo info = termInfo(term);

        return info == null ? null : postings(info);
    }

    public PostingsIterator postings(TermInfo info) {

        return new DiskPostingsIterator(info, docs, positions);
    }

    public int docFreq(String term) {

        TermInfo info = termInfo(term);

        return info == null ? 0 : info.docFreq();
    }

    public int numDocs() {

        return numDocs;
    }

    public long numTerms() {

        return numTerms;
    }

    public long totalTokens() {

        return totalTokens;
    }

    public int docLength(int docId) {

        return docLengths[docId];
    }

    public double averageDocLength() {

        return numDocs == 0 ? 0.0 : (double) totalTokens / numDocs;
    }

    public StoredDocument document(int docId) {

        if (docId < 0 || docId >= numDocs) {

            return null;
        }

        long offset = docsIndex.get(MappedInput.LONG_BE, (long) docId * Long.BYTES);

        MappedInput in = new MappedInput(docsData, offset);

        return new StoredDocument(docId, in.readString(), in.readString(), in.readString());
    }

    /**
     * Unmaps the files. Iterators obtained from this index must not be used
     * afterwards.
     */
    @Override
    public void close() {

        // Idempotent: the server's shutdown hook and main's try-with-resources may both close it.
        if (closed.compareAndSet(false, true)) {

            arena.close();
        }
    }
}
