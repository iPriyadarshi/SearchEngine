package com.searchengine.storage;

import com.searchengine.document.model.Document;
import com.searchengine.engine.SearchEngine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists an analyzed corpus to disk and rebuilds an index from it, so the
 * engine does not have to re-parse documents on every start.
 * <p>
 * The stored artifact is the analyzed token stream per document (order
 * preserved, which is enough to reconstruct either a plain or a positional
 * index) together with the source path. Document content is not stored: it is
 * re-read from the path when available so result snippets can still be shown.
 *
 * <pre>
 * Format (tab separated, one document per line after the header):
 *   SEARCHENGINE-INDEX\t1
 *   &lt;docId&gt;\t&lt;path&gt;\t&lt;token token token ...&gt;
 * </pre>
 */
public class IndexStore {

    private static final String HEADER = "SEARCHENGINE-INDEX";

    private static final String VERSION = "1";

    private static final int GZIP_MAGIC_1 = 0x1f;

    private static final int GZIP_MAGIC_2 = 0x8b;

    public void save(SearchEngine engine, Path file) throws IOException {

        try (OutputStream out = Files.newOutputStream(file)) {

            write(engine, out);
        }
    }

    /**
     * Save a gzip-compressed index. {@link #load} auto-detects compression, so
     * compressed and plain files can be loaded with the same call.
     */
    public void saveCompressed(SearchEngine engine, Path file) throws IOException {

        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {

            write(engine, out);
        }
    }

    private void write(SearchEngine engine, OutputStream out) throws IOException {

        Map<Integer, List<String>> tokensByDoc = engine.getAnalyzedTokens();

        Writer writer = new BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));

        writer.write(HEADER + "\t" + VERSION);

        writer.write("\n");

        for (Map.Entry<Integer, List<String>> entry : tokensByDoc.entrySet()) {

            int docId = entry.getKey();

            Document doc = engine.getDocument(docId);

            String path = doc != null ? doc.getPath() : "";

            writer.write(docId + "\t" + path + "\t" + String.join(" ", entry.getValue()));

            writer.write("\n");
        }

        // Flush the wrapping writer without closing the caller-managed stream
        // early (try-with-resources on `out` still closes the underlying file).
        writer.flush();
    }

    /**
     * Rebuild the index held by {@code engine} from a saved file. The engine
     * must be freshly constructed (its index empty).
     */
    public void load(SearchEngine engine, Path file) throws IOException {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(openMaybeGzip(file), StandardCharsets.UTF_8))) {

            String header = reader.readLine();

            if (header == null || !header.startsWith(HEADER)) {

                throw new IOException("Not a search-engine index file: " + file);
            }

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.isBlank()) {

                    continue;
                }

                // -1 keeps a trailing empty tokens field for documents that
                // analyzed to no terms.
                String[] parts = line.split("\t", -1);

                if (parts.length < 3) {

                    throw new IOException("Malformed index line: " + line);
                }

                int docId = Integer.parseInt(parts[0]);

                String path = parts[1];

                List<String> tokens = parts[2].isEmpty()
                        ? List.of()
                        : List.of(parts[2].split(" "));

                engine.addAnalyzed(new Document(docId, readContent(path), path), tokens);
            }
        }
    }

    /**
     * Open the file, transparently wrapping it in a GZIP stream when the gzip
     * magic bytes are present.
     */
    private InputStream openMaybeGzip(Path file) throws IOException {

        PushbackInputStream in = new PushbackInputStream(Files.newInputStream(file), 2);

        int b1 = in.read();

        int b2 = in.read();

        if (b2 != -1) {

            in.unread(b2);
        }

        if (b1 != -1) {

            in.unread(b1);
        }

        if (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2) {

            return new GZIPInputStream(in);
        }

        return in;
    }

    private String readContent(String path) {

        try {

            Path p = Path.of(path);

            if (Files.isRegularFile(p)) {

                return Files.readString(p);
            }

        } catch (IOException | RuntimeException ignored) {

            // Source document unavailable; snippets simply won't be shown.
        }

        return "";
    }
}
