package com.searchengine.index.disk;

import com.searchengine.api.PostingsIterator;

import java.lang.foreign.MemorySegment;

/**
 * Iterates one term's block-encoded postings straight from the mapped files.
 * <p>
 * A whole block (up to {@link IndexFiles#BLOCK_SIZE} docs) is decoded at a
 * time into small arrays. {@link #advance(int)} binary-searches the skip table
 * to jump directly to the block that can contain the target, so documents in
 * skipped blocks are never decoded. Positions are decoded lazily, only for
 * documents whose positions are actually requested.
 */
final class DiskPostingsIterator implements PostingsIterator {

    private static final int BLOCK = IndexFiles.BLOCK_SIZE;

    private final TermInfo info;

    private final MemorySegment docData;

    private final MappedInput docIn;

    private final MappedInput posIn;

    private final int numBlocks;

    private final int[] docs = new int[BLOCK];

    private final int[] freqs = new int[BLOCK];

    private int block = -1;

    private int count;

    private int index = -1;

    private int doc = -1;

    // Positions decoding state for the current block.
    private long posBlockStart;

    private int posDocIndex;

    DiskPostingsIterator(TermInfo info, MemorySegment docData, MemorySegment posData) {

        this.info = info;

        this.docData = docData;

        this.docIn = new MappedInput(docData, 0);

        this.posIn = new MappedInput(posData, 0);

        this.numBlocks = info.numBlocks();
    }

    @Override
    public int docId() {

        return doc;
    }

    @Override
    public int nextDoc() {

        if (doc == NO_MORE_DOCS) {

            return NO_MORE_DOCS;
        }

        index++;

        if (index >= count) {

            if (block + 1 >= numBlocks) {

                return doc = NO_MORE_DOCS;
            }

            loadBlock(block + 1);
        }

        return doc = docs[index];
    }

    @Override
    public int advance(int target) {

        if (doc == NO_MORE_DOCS || (doc >= target && doc >= 0)) {

            return doc;
        }

        if (block < 0 || docs[count - 1] < target) {

            int next = findBlock(target, block + 1);

            if (next < 0) {

                return doc = NO_MORE_DOCS;
            }

            loadBlock(next);

            // Single-block terms have no skip table, so check the bound here.
            if (docs[count - 1] < target) {

                return doc = NO_MORE_DOCS;
            }
        }

        if (index < 0) {

            index = 0;
        }

        while (docs[index] < target) {

            index++;
        }

        return doc = docs[index];
    }

    /**
     * First block at or after {@code from} whose last doc is {@code >= target},
     * or -1 if none.
     */
    private int findBlock(int target, int from) {

        if (from >= numBlocks) {

            return -1;
        }

        if (numBlocks == 1) {

            return 0;
        }

        int lo = from;

        int hi = numBlocks - 1;

        if (skipField(hi, 0) < target) {

            return -1;
        }

        while (lo < hi) {

            int mid = (lo + hi) >>> 1;

            if (skipField(mid, 0) >= target) {

                hi = mid;

            } else {

                lo = mid + 1;
            }
        }

        return lo;
    }

    private int skipField(int blockIndex, int field) {

        long offset = info.skipOffset() + (long) blockIndex * IndexFiles.SKIP_ENTRY_BYTES + (long) field * Integer.BYTES;

        return docData.get(MappedInput.INT_BE, offset);
    }

    private void loadBlock(int blockIndex) {

        block = blockIndex;

        count = Math.min(BLOCK, info.docFreq() - blockIndex * BLOCK);

        long docOffset = 0;

        long posOffset = 0;

        int base = -1;

        if (numBlocks > 1) {

            docOffset = skipField(blockIndex, 1);

            posOffset = skipField(blockIndex, 2);

            base = blockIndex == 0 ? -1 : skipField(blockIndex - 1, 0);
        }

        docIn.seek(info.docOffset() + docOffset);

        int d = base;

        for (int i = 0; i < count; i++) {

            d += docIn.readVInt();

            docs[i] = d;

            freqs[i] = docIn.readVInt();
        }

        posBlockStart = info.posOffset() + posOffset;

        posIn.seek(posBlockStart);

        posDocIndex = 0;

        index = 0;
    }

    @Override
    public int freq() {

        return freqs[index];
    }

    @Override
    public int[] positions() {

        if (posDocIndex > index) {

            posIn.seek(posBlockStart);

            posDocIndex = 0;
        }

        // Skip over positions of earlier docs in the block that were not read.
        for (; posDocIndex < index; posDocIndex++) {

            for (int i = 0; i < freqs[posDocIndex]; i++) {

                posIn.readVInt();
            }
        }

        int[] result = new int[freqs[index]];

        int position = 0;

        for (int i = 0; i < result.length; i++) {

            position += posIn.readVInt();

            result[i] = position;
        }

        posDocIndex = index + 1;

        return result;
    }

    @Override
    public long cost() {

        return info.docFreq();
    }

    /**
     * Highest term frequency in the current block (for block-max pruning).
     */
    int blockMaxFreq() {

        return numBlocks > 1 ? skipField(block, 3) : info.maxFreq();
    }

    /**
     * Shortest document in the current block (for block-max pruning).
     */
    int blockMinDocLen() {

        return numBlocks > 1 ? skipField(block, 4) : info.minDocLen();
    }
}
