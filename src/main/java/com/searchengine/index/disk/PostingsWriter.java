package com.searchengine.index.disk;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Writes the final, block-encoded postings for one term at a time.
 * <p>
 * Documents are grouped into blocks of {@link IndexFiles#BLOCK_SIZE}. Doc ids
 * are delta-encoded across the whole list, so a block is decoded starting from
 * the previous block's last doc id (taken from the skip table). Positions go
 * to a separate file so ranked queries never touch them.
 */
final class PostingsWriter implements Closeable {

    private static final int BLOCK = IndexFiles.BLOCK_SIZE;

    private final IndexOutput docOut;

    private final IndexOutput posOut;

    private final int[] docLengths;

    // Current term.
    private String term;

    private int docFreq;

    private long totalFreq;

    private int maxFreq;

    private int minDocLen;

    private long docStart;

    private long posStart;

    private int lastDoc;

    // Current (unflushed) block.
    private final int[] blockDocs = new int[BLOCK];

    private final int[] blockFreqs = new int[BLOCK];

    private int blockCount;

    private int blockMaxFreq;

    private int blockMinDocLen;

    private long blockPosStart;

    // Skip table for the current term; flattened SKIP_FIELDS ints per block.
    private static final int SKIP_FIELDS = IndexFiles.SKIP_ENTRY_BYTES / Integer.BYTES;

    private int[] skip = new int[SKIP_FIELDS * 16];

    private int numBlocks;

    PostingsWriter(Path dir, int[] docLengths) throws IOException {

        this.docOut = new IndexOutput(dir.resolve(IndexFiles.POSTINGS_DOC));

        this.posOut = new IndexOutput(dir.resolve(IndexFiles.POSTINGS_POS));

        this.docLengths = docLengths;
    }

    void startTerm(String term) {

        this.term = term;

        docFreq = 0;

        totalFreq = 0;

        maxFreq = 0;

        minDocLen = Integer.MAX_VALUE;

        docStart = docOut.position();

        posStart = posOut.position();

        lastDoc = -1;

        blockCount = 0;

        numBlocks = 0;
    }

    /**
     * Add one document; doc ids must be strictly increasing within a term.
     */
    void addDoc(int docId, int freq, int[] positions) throws IOException {

        if (docId <= lastDocInTerm()) {

            throw new IllegalStateException("Doc ids out of order for term '" + term + "': " + docId);
        }

        if (blockCount == 0) {

            blockPosStart = posOut.position() - posStart;

            blockMaxFreq = 0;

            blockMinDocLen = Integer.MAX_VALUE;
        }

        int docLen = docLengths[docId];

        blockDocs[blockCount] = docId;

        blockFreqs[blockCount] = freq;

        blockCount++;

        blockMaxFreq = Math.max(blockMaxFreq, freq);

        blockMinDocLen = Math.min(blockMinDocLen, docLen);

        int previous = 0;

        for (int i = 0; i < freq; i++) {

            posOut.writeVInt(positions[i] - previous);

            previous = positions[i];
        }

        docFreq++;

        totalFreq += freq;

        if (blockCount == BLOCK) {

            flushBlock();
        }
    }

    private int lastDocInTerm() {

        return blockCount > 0 ? blockDocs[blockCount - 1] : lastDoc;
    }

    private void flushBlock() throws IOException {

        long blockDocStart = docOut.position() - docStart;

        for (int i = 0; i < blockCount; i++) {

            docOut.writeVInt(blockDocs[i] - lastDoc);

            docOut.writeVInt(blockFreqs[i]);

            lastDoc = blockDocs[i];
        }

        if ((numBlocks + 1) * SKIP_FIELDS > skip.length) {

            skip = Arrays.copyOf(skip, skip.length * 2);
        }

        int base = numBlocks * SKIP_FIELDS;

        skip[base] = lastDoc;

        skip[base + 1] = Math.toIntExact(blockDocStart);

        skip[base + 2] = Math.toIntExact(blockPosStart);

        skip[base + 3] = blockMaxFreq;

        skip[base + 4] = blockMinDocLen;

        numBlocks++;

        maxFreq = Math.max(maxFreq, blockMaxFreq);

        minDocLen = Math.min(minDocLen, blockMinDocLen);

        blockCount = 0;
    }

    TermInfo finishTerm() throws IOException {

        if (blockCount > 0) {

            flushBlock();
        }

        long skipOffset = -1;

        if (numBlocks > 1) {

            skipOffset = docOut.position();

            for (int i = 0; i < numBlocks * SKIP_FIELDS; i++) {

                docOut.writeInt(skip[i]);
            }
        }

        return new TermInfo(term, docFreq, totalFreq, docStart, posStart, skipOffset, maxFreq, minDocLen);
    }

    @Override
    public void close() throws IOException {

        try (docOut; posOut) {
            // closes both, even if one fails
        }
    }
}
