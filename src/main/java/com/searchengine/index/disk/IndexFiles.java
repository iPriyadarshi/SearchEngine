package com.searchengine.index.disk;

/**
 * File names and layout constants shared by {@link DiskIndexWriter} and
 * {@link DiskIndex}.
 *
 * <pre>
 * meta.properties   corpus statistics and format version
 * terms.dict        sorted term dictionary (one {@link TermInfo} per term)
 * terms.idx         every {@value #DICT_INDEX_INTERVAL}th term + its offset in terms.dict
 * postings.doc      per term: blocks of {@value #BLOCK_SIZE} (docDelta, freq) varints,
 *                   followed by a fixed-width skip table when the term has &gt; 1 block
 * postings.pos      per term: position deltas for every (doc, occurrence), in doc order
 * doclens.bin       int count, then the token length of every document
 * docs.dat          stored fields per document: title, path, snippet text
 * docs.idx          8-byte offset of each document's record in docs.dat
 * pagerank.bin      int count, then each document's PageRank as a float (mean 1.0)
 * domainrank.bin    int count, then each document's site popularity rank (0 = unranked)
 * vocab.bin         words and document counts, for spelling correction
 * suggest.bin       autocomplete phrases (sorted) with popularity scores
 * </pre>
 *
 * Besides body terms, the dictionary holds field terms marked by a prefix
 * the tokenizer can never produce: {@value #TITLE_FIELD} title,
 * {@value #URL_FIELD} URL (host and path words), {@value #ANCHOR_FIELD} anchor
 * text of links pointing at the document.
 *
 * Skip table entry (all big-endian ints, {@value #SKIP_ENTRY_BYTES} bytes):
 * last doc id in block, block offset in postings.doc (relative to the term's
 * start), block offset in postings.pos (relative), max term frequency in block,
 * min document length in block. The last two let rankers compute per-block
 * score upper bounds.
 */
public final class IndexFiles {

    static final int FORMAT_VERSION = 1;

    static final int BLOCK_SIZE = 128;

    static final int DICT_INDEX_INTERVAL = 64;

    static final int SKIP_ENTRY_BYTES = 5 * Integer.BYTES;

    static final String META = "meta.properties";

    static final String TERMS_DICT = "terms.dict";

    static final String TERMS_INDEX = "terms.idx";

    static final String POSTINGS_DOC = "postings.doc";

    static final String POSTINGS_POS = "postings.pos";

    static final String DOC_LENGTHS = "doclens.bin";

    static final String DOCS_DATA = "docs.dat";

    static final String DOCS_INDEX = "docs.idx";

    static final String TEMP_DIR = "tmp";

    static final String PAGERANK = "pagerank.bin";

    static final String DOMAIN_RANK = "domainrank.bin";

    static final String VOCABULARY = "vocab.bin";

    static final String SUGGESTIONS = "suggest.bin";

    public static final String TITLE_FIELD = "~";

    public static final String URL_FIELD = "^";

    public static final String ANCHOR_FIELD = "@";

    private IndexFiles() {
    }
}
