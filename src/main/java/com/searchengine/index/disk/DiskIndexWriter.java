package com.searchengine.index.disk;

import com.searchengine.api.Parser;
import com.searchengine.document.model.Document;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Set;
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
 * <p>
 * Besides the body, each document's title and URL words are indexed as
 * prefixed field terms ({@link IndexFiles#TITLE_FIELD}, {@link IndexFiles#URL_FIELD}).
 * Outgoing links feed a {@link LinkGraph}; on close, anchor text is indexed as
 * a field of the pages it points to ({@link IndexFiles#ANCHOR_FIELD}) and
 * PageRank is computed. With {@link #setDeduplicate} on, a page whose URL or
 * text was already added is skipped ({@link #add} returns -1).
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

    private final LinkGraph links;

    private final QueryAidsBuilder aids = new QueryAidsBuilder();

    private final IntList domainRanks = new IntList();

    private Map<String, Integer> domainRankTable = Map.of();

    private boolean deduplicate;

    private final Set<Long> contentHashes = new HashSet<>();

    private int skippedDuplicates;

    // URL words that carry no meaning.
    private static final Set<String> URL_NOISE = Set.of(
            "www", "http", "https", "com", "org", "net", "html", "htm", "php", "asp", "aspx", "jsp", "index",
            "default", "en", "us", "uk", "amp");

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

        this.links = new LinkGraph(tempDir);
    }

    /**
     * Skip documents whose URL or text duplicates one already added.
     */
    public void setDeduplicate(boolean deduplicate) {

        this.deduplicate = deduplicate;
    }

    /**
     * Site popularity ranks (domain, e.g. "python.org", to rank 1..N) recorded
     * for each added document.
     */
    public void setDomainRanks(Map<String, Integer> ranks) {

        this.domainRankTable = ranks;
    }

    public int skippedDuplicates() {

        return skippedDuplicates;
    }

    public DiskIndexWriter(Path dir, Parser parser, long memoryBudget) throws IOException {

        this(dir, parser, memoryBudget, message -> { });
    }

    /**
     * Analyze and buffer one document; returns the doc id assigned to it.
     */
    public int add(Document doc) throws IOException {

        return add(doc, parser.parse(doc));
    }

    /**
     * Buffer a document that was already analyzed (e.g. on a worker thread).
     * Not thread-safe: calls must come from a single thread, in doc-id order.
     */
    public int add(Document doc, List<String> tokens) throws IOException {

        if (closed) {

            throw new IllegalStateException("Writer is closed");
        }

        String url = doc.getPath() == null ? "" : doc.getPath();

        if (deduplicate) {

            if (!url.isEmpty() && links.contains(url)) {

                skippedDuplicates++;

                return -1;
            }

            String content = doc.getContent() == null ? "" : doc.getContent();

            if (content.length() >= 200 && !contentHashes.add(LinkGraph.hash(content))) {

                skippedDuplicates++;

                return -1;
            }
        }

        int docId = docLengths.size();

        // The author's summary (meta description) counts as body text, after the page text.
        List<String> description = doc.getDescription().isEmpty() ? List.of() : analyze(doc.getDescription());

        int bodyLength = tokens.size() + description.size();

        docLengths.add(bodyLength);

        totalTokens += bodyLength;

        storeDocument(doc);

        links.addPage(docId, url, doc.getLinks());

        domainRanks.add(domainRank(url));

        docPositions.clear();

        addField("", tokens, 0);

        addField("", description, tokens.size() + 10);

        if (!doc.getTitle().isEmpty()) {

            addField(IndexFiles.TITLE_FIELD, analyze(doc.getTitle()), 0);
        }

        String urlWords = urlWords(url);

        if (!urlWords.isEmpty()) {

            addField(IndexFiles.URL_FIELD, analyze(urlWords), 0);
        }

        bufferPostings(docId);

        if ((docId + 1) % PROGRESS_INTERVAL == 0) {

            double seconds = (System.nanoTime() - startNanos) / 1e9;

            log.accept(String.format("  indexed %,d docs (%,.0f docs/s), %d segment(s) flushed",
                    docId + 1, (docId + 1) / seconds, segments.size()));
        }

        return docId;
    }

    private List<String> analyze(String text) {

        return parser.parse(new Document(-1, text, ""));
    }

    /**
     * Record a field's tokens for the current document, at positions
     * {@code offset + i} within the field.
     */
    private void addField(String prefix, List<String> tokens, int offset) {

        for (int position = 0; position < tokens.size(); position++) {

            String token = tokens.get(position);

            if (!token.isEmpty() && token.length() <= MAX_TERM_LENGTH) {

                docPositions.computeIfAbsent(prefix + token, k -> new IntList()).add(offset + position);
            }
        }
    }

    /**
     * Host and path words of a URL, e.g. "https://docs.python.org/3/tutorial/"
     * gives "docs python 3 tutorial".
     */
    public static String urlWords(String url) {

        if (!url.startsWith("http://") && !url.startsWith("https://")) {

            return "";
        }

        String rest = url.substring(url.indexOf("://") + 3);

        int query = rest.indexOf('?');

        if (query >= 0) {

            rest = rest.substring(0, query);
        }

        StringBuilder words = new StringBuilder();

        for (String word : rest.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {

            // Long digit runs are ids, not words.
            boolean id = word.length() > 4 && word.chars().allMatch(Character::isDigit);

            if (!word.isEmpty() && !URL_NOISE.contains(word) && !id) {

                words.append(word).append(' ');
            }
        }

        return words.toString().strip();
    }

    private int domainRank(String url) {

        if (domainRankTable.isEmpty() || url.isEmpty()) {

            return 0;
        }

        String host;

        try {

            host = URI.create(url).getHost();

        } catch (IllegalArgumentException e) {

            return 0;
        }

        if (host == null) {

            return 0;
        }

        // Try the full host, then each parent domain: docs.python.org, python.org.
        String candidate = host.toLowerCase(Locale.ROOT);

        while (candidate.contains(".")) {

            Integer rank = domainRankTable.get(candidate);

            if (rank != null) {

                return rank;
            }

            candidate = candidate.substring(candidate.indexOf('.') + 1);
        }

        return 0;
    }

    /**
     * Move the current document's positions into the per-term buffers and
     * flush a segment if the memory budget is exceeded.
     */
    private void bufferPostings(int docId) throws IOException {

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
    }

    public int documentCount() {

        return docLengths.size();
    }

    private void storeDocument(Document doc) throws IOException {

        docsIndex.writeLong(docsData.position());

        String snippet = snippetText(doc);

        docsData.writeString(doc.getTitle());

        docsData.writeString(doc.getPath() == null ? "" : doc.getPath());

        docsData.writeString(snippet);

        aids.addDocument(docLengths.size() - 1, doc.getTitle(), snippet);
    }

    private static String snippetText(Document doc) {

        String content = doc.getContent() == null ? "" : doc.getContent();

        String title = doc.getTitle();

        // Avoid repeating the title when the content starts with it.
        if (!title.isEmpty() && content.startsWith(title)) {

            content = content.substring(title.length());
        }

        // A meta description is the author's own summary: show it first.
        if (!doc.getDescription().isEmpty()) {

            content = doc.getDescription() + " " + content;
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

        // Anchor text is replayed in ascending target order, so it can be buffered like any field.
        float[] pageRank = links.finish(lengths.length, (docId, anchors) -> {

            docPositions.clear();

            int offset = 0;

            for (String anchor : anchors) {

                List<String> tokens = analyze(anchor);

                for (int i = 0; i < tokens.size(); i++) {

                    String token = tokens.get(i);

                    if (!token.isEmpty() && token.length() <= MAX_TERM_LENGTH) {

                        docPositions.computeIfAbsent(IndexFiles.ANCHOR_FIELD + token, k -> new IntList()).add(offset + i);
                    }
                }

                // A gap keeps phrases from spanning two different anchors.
                offset += tokens.size() + 10;
            }

            bufferPostings(docId);
        });

        flushSegment();

        links.close();

        writeFloats(IndexFiles.PAGERANK, pageRank);

        int[] siteRanks = domainRanks.toArray();

        writeInts(IndexFiles.DOMAIN_RANK, siteRanks);

        aids.write(dir, pageRank, siteRanks);

        if (skippedDuplicates > 0) {

            log.accept(String.format("  skipped %,d duplicate page(s)", skippedDuplicates));
        }

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

    private void writeFloats(String name, float[] values) throws IOException {

        try (IndexOutput out = new IndexOutput(dir.resolve(name))) {

            out.writeInt(values.length);

            for (float value : values) {

                out.writeInt(Float.floatToIntBits(value));
            }
        }
    }

    private void writeInts(String name, int[] values) throws IOException {

        try (IndexOutput out = new IndexOutput(dir.resolve(name))) {

            out.writeInt(values.length);

            for (int value : values) {

                out.writeInt(value);
            }
        }
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
