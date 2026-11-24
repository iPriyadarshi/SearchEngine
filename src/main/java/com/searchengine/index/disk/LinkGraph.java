package com.searchengine.index.disk;

import com.searchengine.document.model.Link;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the link graph of the indexed pages while they are added, then
 * resolves it once every page is known.
 * <ol>
 * <li>While indexing, each page's URL is recorded (as a 64-bit hash) and its
 * outgoing links are spooled to a temporary file, so the graph never has to
 * fit in memory as strings.</li>
 * <li>{@link #finish} resolves links to indexed pages, writes each anchor text
 * to a bucket file by target doc id, and replays the buckets in ascending
 * target order - the order postings must be written in.</li>
 * <li>PageRank is computed over the resolved edges by power iteration.</li>
 * </ol>
 * Links to pages outside the index are dropped: they cannot be ranked.
 */
final class LinkGraph implements Closeable {

    static final int BUCKET_DOCS = 1 << 16;

    static final int MAX_ANCHOR_CHARS_PER_DOC = 2_000;

    static final double DAMPING = 0.85;

    static final int MAX_ITERATIONS = 40;

    static final double TOLERANCE = 1e-7;

    // Anchors that describe nothing about their target.
    private static final Set<String> GENERIC_ANCHORS = Set.of(
            "click here", "here", "read more", "more", "link", "this", "learn more", "next", "previous", "prev",
            "continue", "details", "view", "see more", "skip to content", "skip to main content", "menu", "back",
            "top", "back to top", "go", "open", "show more", "view more", "more info", "website", "visit");

    @FunctionalInterface
    interface AnchorSink {

        void accept(int docId, List<String> anchors) throws IOException;
    }

    private final Path tempDir;

    private final Map<Long, Integer> urlToDoc = new HashMap<>();

    private IndexOutput spool;

    private boolean hasLinks;

    LinkGraph(Path tempDir) {

        this.tempDir = tempDir;
    }

    static long hash(String url) {

        // 64-bit FNV-1a: collisions among a few million URLs are negligible.
        long h = 0xcbf29ce484222325L;

        for (int i = 0; i < url.length(); i++) {

            h ^= url.charAt(i);

            h *= 0x100000001b3L;
        }

        return h;
    }

    boolean contains(String url) {

        return urlToDoc.containsKey(hash(url));
    }

    void addPage(int docId, String url, List<Link> links) throws IOException {

        if (url != null && !url.isEmpty()) {

            urlToDoc.putIfAbsent(hash(url), docId);
        }

        if (links.isEmpty()) {

            return;
        }

        if (spool == null) {

            spool = new IndexOutput(tempDir.resolve("links.tmp"));
        }

        hasLinks = true;

        spool.writeVInt(docId);

        spool.writeVInt(links.size());

        for (Link link : links) {

            spool.writeLong(hash(link.url()));

            spool.writeString(link.anchor() == null ? "" : link.anchor());
        }
    }

    boolean hasLinks() {

        return hasLinks;
    }

    /**
     * Resolve the spooled links, replay anchor text per target in ascending
     * doc-id order, and return PageRank scaled so the mean is 1.0.
     */
    float[] finish(int numDocs, AnchorSink sink) throws IOException {

        if (!hasLinks) {

            float[] uniform = new float[numDocs];

            Arrays.fill(uniform, 1f);

            return uniform;
        }

        spool.close();

        DocIdList sources = new DocIdList();

        DocIdList targets = new DocIdList();

        int buckets = Math.max(1, (numDocs + BUCKET_DOCS - 1) / BUCKET_DOCS);

        IndexOutput[] bucketOut = new IndexOutput[buckets];

        try (DataInputStream in = open(tempDir.resolve("links.tmp"))) {

            Set<Integer> seen = new HashSet<>();

            while (true) {

                int src;

                try {

                    src = readVInt(in);

                } catch (EOFException end) {

                    break;
                }

                int count = readVInt(in);

                seen.clear();

                for (int i = 0; i < count; i++) {

                    long target = in.readLong();

                    String anchor = readString(in);

                    Integer dst = urlToDoc.get(target);

                    if (dst == null || dst == src) {

                        continue;
                    }

                    if (seen.add(dst)) {

                        sources.add(src);

                        targets.add(dst);
                    }

                    if (isDescriptive(anchor)) {

                        int bucket = dst / BUCKET_DOCS;

                        if (bucketOut[bucket] == null) {

                            bucketOut[bucket] = new IndexOutput(tempDir.resolve("anchors-" + bucket + ".tmp"));
                        }

                        bucketOut[bucket].writeVInt(dst);

                        bucketOut[bucket].writeString(anchor);
                    }
                }
            }
        }

        for (IndexOutput out : bucketOut) {

            if (out != null) {

                out.close();
            }
        }

        replayAnchors(bucketOut, sink);

        Files.deleteIfExists(tempDir.resolve("links.tmp"));

        return pageRank(numDocs, sources.toArray(), targets.toArray());
    }

    private void replayAnchors(IndexOutput[] buckets, AnchorSink sink) throws IOException {

        for (int b = 0; b < buckets.length; b++) {

            if (buckets[b] == null) {

                continue;
            }

            Path file = tempDir.resolve("anchors-" + b + ".tmp");

            DocIdList dsts = new DocIdList();

            List<String> anchors = new ArrayList<>();

            try (DataInputStream in = open(file)) {

                while (true) {

                    try {

                        dsts.add(readVInt(in));

                    } catch (EOFException end) {

                        break;
                    }

                    anchors.add(readString(in));
                }
            }

            // Stable sort of record indexes by target doc id.
            Integer[] order = new Integer[dsts.size()];

            for (int i = 0; i < order.length; i++) {

                order[i] = i;
            }

            Arrays.sort(order, (x, y) -> Integer.compare(dsts.get(x), dsts.get(y)));

            int i = 0;

            while (i < order.length) {

                int doc = dsts.get(order[i]);

                List<String> forDoc = new ArrayList<>();

                Set<String> distinct = new HashSet<>();

                int chars = 0;

                for (; i < order.length && dsts.get(order[i]) == doc; i++) {

                    String anchor = anchors.get(order[i]);

                    // Repeated identical anchors (site navigation) count once per distinct text.
                    if (chars < MAX_ANCHOR_CHARS_PER_DOC && distinct.add(anchor.toLowerCase(Locale.ROOT))) {

                        forDoc.add(anchor);

                        chars += anchor.length();
                    }
                }

                sink.accept(doc, forDoc);
            }

            Files.deleteIfExists(file);
        }
    }

    static boolean isDescriptive(String anchor) {

        String a = anchor.strip().toLowerCase(Locale.ROOT);

        return a.length() >= 2 && !GENERIC_ANCHORS.contains(a);
    }

    /**
     * Power-iteration PageRank with uniform teleport; rank from dangling pages
     * (no outgoing edges) is spread evenly. Edges arrive grouped by source.
     */
    static float[] pageRank(int n, int[] sources, int[] targets) {

        float[] result = new float[n];

        if (n == 0) {

            return result;
        }

        int[] outDegree = new int[n];

        for (int s : sources) {

            outDegree[s]++;
        }

        double[] rank = new double[n];

        Arrays.fill(rank, 1.0 / n);

        double[] next = new double[n];

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {

            Arrays.fill(next, 0);

            double dangling = 0;

            for (int v = 0; v < n; v++) {

                if (outDegree[v] == 0) {

                    dangling += rank[v];
                }
            }

            for (int e = 0; e < sources.length; e++) {

                next[targets[e]] += rank[sources[e]] / outDegree[sources[e]];
            }

            double base = (1 - DAMPING) / n + DAMPING * dangling / n;

            double delta = 0;

            for (int v = 0; v < n; v++) {

                double value = base + DAMPING * next[v];

                delta += Math.abs(value - rank[v]);

                next[v] = value;
            }

            double[] swap = rank;

            rank = next;

            next = swap;

            if (delta < TOLERANCE) {

                break;
            }
        }

        for (int v = 0; v < n; v++) {

            result[v] = (float) (rank[v] * n);
        }

        return result;
    }

    private static DataInputStream open(Path file) throws IOException {

        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
    }

    private static int readVInt(DataInputStream in) throws IOException {

        int b = in.readUnsignedByte();

        int value = b & 0x7F;

        for (int shift = 7; (b & 0x80) != 0; shift += 7) {

            b = in.readUnsignedByte();

            value |= (b & 0x7F) << shift;
        }

        return value;
    }

    private static String readString(DataInputStream in) throws IOException {

        byte[] bytes = new byte[readVInt(in)];

        in.readFully(bytes);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {

        if (spool != null) {

            spool.close();
        }
    }

    /**
     * Growable int array.
     */
    static final class DocIdList {

        private int[] values = new int[16];

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
