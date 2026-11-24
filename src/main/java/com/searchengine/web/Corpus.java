package com.searchengine.web;

import com.searchengine.document.model.Document;
import com.searchengine.document.model.Link;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A local store of extracted web pages, kept separate from the index.
 * <p>
 * Fetching (from Common Crawl or the crawler) is slow and network-bound;
 * indexing is fast and changes as ranking evolves. Storing the extracted
 * pages - title, description, text and links, roughly 1% of the raw archive -
 * means the index can be rebuilt any number of times without fetching again.
 * <p>
 * A corpus is a directory of {@code *.docs.gz} files. Each is a gzip stream:
 * a magic string, then per page the URL, title, description, text and links
 * (UTF strings, chunked for long text), then an end marker. Files are written
 * to a temporary name and renamed when complete, so a partial file is never
 * mistaken for a finished one.
 */
public final class Corpus {

    static final String MAGIC = "SIFT-CORPUS-1";

    public static final String SUFFIX = ".docs.gz";

    // DataOutput.writeUTF is limited to 64 KB of encoded bytes.
    private static final int UTF_CHUNK_CHARS = 16_000;

    private Corpus() {
    }

    /**
     * Writes one corpus file; call {@link #commit()} when every page is written.
     */
    public static final class Writer implements Closeable {

        private final Path target;

        private final Path temp;

        private final DataOutputStream out;

        private int pages;

        private boolean committed;

        public Writer(Path target) throws IOException {

            this.target = target;

            this.temp = target.resolveSibling(target.getFileName() + ".part");

            Files.createDirectories(target.toAbsolutePath().getParent());

            this.out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(temp), 1 << 16), 1 << 16));

            out.writeUTF(MAGIC);
        }

        public void write(Document doc) throws IOException {

            out.writeBoolean(true);

            writeLong(doc.getPath() == null ? "" : doc.getPath());

            writeLong(doc.getTitle());

            writeLong(doc.getDescription());

            writeLong(doc.getContent() == null ? "" : doc.getContent());

            out.writeInt(doc.getLinks().size());

            for (Link link : doc.getLinks()) {

                writeLong(link.url());

                writeLong(link.anchor());
            }

            pages++;
        }

        private void writeLong(String value) throws IOException {

            int chunks = Math.max(1, (value.length() + UTF_CHUNK_CHARS - 1) / UTF_CHUNK_CHARS);

            out.writeInt(chunks);

            for (int i = 0; i < chunks; i++) {

                out.writeUTF(value.substring(i * UTF_CHUNK_CHARS, Math.min(value.length(), (i + 1) * UTF_CHUNK_CHARS)));
            }
        }

        public int pages() {

            return pages;
        }

        /**
         * Finish the file and move it into place.
         */
        public void commit() throws IOException {

            out.writeBoolean(false);

            out.close();

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            committed = true;
        }

        /**
         * Without a commit, the partial file is discarded.
         */
        @Override
        public void close() throws IOException {

            if (!committed) {

                out.close();

                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Every complete corpus file in {@code dir}, in name order.
     */
    public static List<Path> files(Path dir) throws IOException {

        try (Stream<Path> files = Files.list(dir)) {

            return files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
    }

    /**
     * Pages of all given corpus files, read lazily one file at a time.
     */
    public static Reader read(List<Path> files) {

        return new Reader(files);
    }

    public static final class Reader implements Iterator<Document>, AutoCloseable {

        private final Iterator<Path> files;

        private DataInputStream in;

        private Document next;

        Reader(List<Path> files) {

            this.files = new ArrayList<>(files).iterator();
        }

        @Override
        public boolean hasNext() {

            try {

                while (next == null) {

                    if (in == null) {

                        if (!files.hasNext()) {

                            return false;
                        }

                        in = open(files.next());
                    }

                    next = readPage(in);

                    if (next == null) {

                        close();
                    }
                }

                return true;

            } catch (IOException e) {

                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Document next() {

            if (!hasNext()) {

                throw new NoSuchElementException();
            }

            Document doc = next;

            next = null;

            return doc;
        }

        private static DataInputStream open(Path file) throws IOException {

            DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16));

            if (!MAGIC.equals(in.readUTF())) {

                in.close();

                throw new IOException("Not a corpus file: " + file);
            }

            return in;
        }

        private static Document readPage(DataInputStream in) throws IOException {

            try {

                if (!in.readBoolean()) {

                    return null;
                }

                String url = readLong(in);

                String title = readLong(in);

                String description = readLong(in);

                String content = readLong(in);

                int count = in.readInt();

                List<Link> links = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {

                    links.add(new Link(readLong(in), readLong(in)));
                }

                return new Document(0, title, description, content, url, links);

            } catch (EOFException e) {

                throw new IOException("Truncated corpus file", e);
            }
        }

        private static String readLong(DataInputStream in) throws IOException {

            int chunks = in.readInt();

            if (chunks == 1) {

                return in.readUTF();
            }

            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < chunks; i++) {

                sb.append(in.readUTF());
            }

            return sb.toString();
        }

        @Override
        public void close() throws IOException {

            if (in != null) {

                in.close();

                in = null;
            }
        }
    }

    /**
     * Name of the corpus file for a source file, e.g. a WARC URL.
     */
    public static String nameFor(String source) {

        String name = source.substring(source.lastIndexOf('/') + 1);

        for (String suffix : new String[]{".warc.gz", ".warc", ".gz"}) {

            if (name.endsWith(suffix)) {

                name = name.substring(0, name.length() - suffix.length());

                break;
            }
        }

        return name + SUFFIX;
    }
}
