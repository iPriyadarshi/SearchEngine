package com.searchengine.index.disk;

/**
 * Dictionary entry for one term: its statistics and where its postings live.
 *
 * @param docFreq    number of documents containing the term
 * @param totalFreq  total occurrences across the corpus
 * @param docOffset  start of the term's blocks in postings.doc
 * @param posOffset  start of the term's positions in postings.pos
 * @param skipOffset start of the skip table in postings.doc, or -1 for single-block terms
 * @param maxFreq    highest term frequency in any document (for score upper bounds)
 * @param minDocLen  shortest document containing the term (for BM25 upper bounds)
 */
public record TermInfo(String term,
                       int docFreq,
                       long totalFreq,
                       long docOffset,
                       long posOffset,
                       long skipOffset,
                       int maxFreq,
                       int minDocLen) {

    public int numBlocks() {

        return (docFreq + IndexFiles.BLOCK_SIZE - 1) / IndexFiles.BLOCK_SIZE;
    }

    void write(IndexOutput out) throws java.io.IOException {

        out.writeString(term);

        out.writeVInt(docFreq);

        out.writeVLong(totalFreq);

        out.writeVLong(docOffset);

        out.writeVLong(posOffset);

        out.writeVLong(skipOffset + 1);

        out.writeVInt(maxFreq);

        out.writeVInt(minDocLen);
    }

    static TermInfo read(MappedInput in) {

        return new TermInfo(in.readString(), in.readVInt(), in.readVLong(), in.readVLong(),
                in.readVLong(), in.readVLong() - 1, in.readVInt(), in.readVInt());
    }
}
