package com.searchengine.document.source;

import com.searchengine.api.DocumentSource;
import com.searchengine.document.model.Document;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;

/**
 * Streams articles out of a MediaWiki XML dump (e.g.
 * {@code enwiki-latest-pages-articles.xml.bz2}).
 * <p>
 * The dump is read with a StAX pull parser, so only one page is in memory at a
 * time. {@code .bz2} and {@code .gz} dumps are decompressed on the fly;
 * multistream {@code .bz2} dumps are decompressed on several threads (see
 * {@link ParallelBzip2InputStream}). Only main-namespace articles are emitted;
 * redirects and talk/user/template pages are skipped. Each article's wikitext
 * is converted to plain text with {@link WikitextCleaner}.
 * <p>
 * {@link #stream()} cleans pages on the calling thread. For parallel indexing
 * use {@link #rawPages()} and run {@link #toDocument(WikiPage)} on workers.
 */
public class WikipediaDumpSource implements DocumentSource {

    private static final String DEFAULT_URL_PREFIX = "https://en.wikipedia.org/wiki/";

    private static final int BUFFER_SIZE = 1 << 16;

    private final Path dump;

    private final long maxDocuments;

    private final int decompressionThreads;

    /**
     * An article as it appears in the dump, before markup is removed.
     */
    public record WikiPage(String title, String wikitext, String url) {
    }

    public WikipediaDumpSource(Path dump) {

        this(dump, Long.MAX_VALUE);
    }

    /**
     * @param maxDocuments stop after this many articles (useful for sampling)
     */
    public WikipediaDumpSource(Path dump, long maxDocuments) {

        this(dump, maxDocuments, 1);
    }

    /**
     * @param decompressionThreads threads for decompressing multistream bzip2 dumps
     */
    public WikipediaDumpSource(Path dump, long maxDocuments, int decompressionThreads) {

        this.dump = dump;

        this.maxDocuments = maxDocuments;

        this.decompressionThreads = decompressionThreads;
    }

    /**
     * Loads every article into memory. Only sensible for small dumps or a
     * bounded {@code maxDocuments}; prefer {@link #stream()}.
     */
    @Override
    public List<Document> loadDocuments() throws IOException {

        try (Stream<Document> docs = stream()) {

            return docs.toList();
        }
    }

    @Override
    public Stream<Document> stream() throws IOException {

        int[] nextId = {0};

        return rawPages(Long.MAX_VALUE)
                .map(WikipediaDumpSource::toDocument)
                .filter(Objects::nonNull)
                .limit(maxDocuments)
                .map(doc -> new Document(nextId[0]++, doc.getTitle(), doc.getContent(), doc.getPath()));
    }

    /**
     * Stream articles without cleaning them, at most {@code maxDocuments}.
     */
    public Stream<WikiPage> rawPages() throws IOException {

        return rawPages(maxDocuments);
    }

    /**
     * Convert a raw page into an indexable document, or {@code null} if the
     * page has no text left after cleaning. Thread-safe. The document id is 0;
     * indexers assign their own ids.
     */
    public static Document toDocument(WikiPage page) {

        String text = WikitextCleaner.clean(page.wikitext());

        if (text.isEmpty()) {

            return null;
        }

        String prefix = page.url().substring(0, page.url().length() - page.title().replace(' ', '_').length());

        return new Document(0, page.title(), "", page.title() + "\n\n" + text, page.url(),
                WikitextCleaner.links(page.wikitext(), prefix));
    }

    private Stream<WikiPage> rawPages(long limit) throws IOException {

        InputStream in = open(dump);

        try {

            XMLStreamReader reader = newFactory().createXMLStreamReader(in, "UTF-8");

            PageIterator pages = new PageIterator(reader, limit);

            return StreamSupport
                    .stream(Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL), false)
                    .onClose(() -> {

                        try {

                            reader.close();

                            in.close();

                        } catch (XMLStreamException | IOException e) {

                            throw new RuntimeException(e);
                        }
                    });

        } catch (XMLStreamException e) {

            in.close();

            throw new IOException("Cannot read dump " + dump, e);
        }
    }

    private InputStream open(Path file) throws IOException {

        String name = file.getFileName().toString().toLowerCase();

        if (name.endsWith(".bz2")) {

            return new BufferedInputStream(ParallelBzip2InputStream.open(file, decompressionThreads), BUFFER_SIZE);
        }

        InputStream raw = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE);

        if (name.endsWith(".gz")) {

            return new BufferedInputStream(new GZIPInputStream(raw, BUFFER_SIZE), BUFFER_SIZE);
        }

        return raw;
    }

    private static XMLInputFactory newFactory() {

        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();

        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        // A full dump decodes billions of character references (&quot; etc.);
        // the JDK's default accumulated-entity limits would abort the parse.
        for (String limit : List.of("jdk.xml.totalEntitySizeLimit", "jdk.xml.maxGeneralEntitySizeLimit",
                "jdk.xml.entityExpansionLimit")) {

            try {

                factory.setProperty(limit, "0");

            } catch (IllegalArgumentException ignored) {
                // Not supported by this StAX implementation.
            }
        }

        return factory;
    }

    /**
     * Pulls one article at a time from the XML stream.
     */
    private final class PageIterator implements Iterator<WikiPage> {

        private final XMLStreamReader reader;

        private final long limit;

        private String urlPrefix = DEFAULT_URL_PREFIX;

        private WikiPage next;

        private long emitted = 0;

        private boolean finished = false;

        PageIterator(XMLStreamReader reader, long limit) {

            this.reader = reader;

            this.limit = limit;
        }

        @Override
        public boolean hasNext() {

            if (next == null && !finished) {

                next = readNextArticle();

                finished = next == null;
            }

            return next != null;
        }

        @Override
        public WikiPage next() {

            if (!hasNext()) {

                throw new NoSuchElementException();
            }

            WikiPage page = next;

            next = null;

            return page;
        }

        private WikiPage readNextArticle() {

            if (emitted >= limit) {

                return null;
            }

            try {

                while (reader.hasNext()) {

                    if (reader.next() != XMLStreamConstants.START_ELEMENT) {

                        continue;
                    }

                    String element = reader.getLocalName();

                    if (element.equals("base")) {

                        urlPrefix = prefixOf(reader.getElementText());

                    } else if (element.equals("page")) {

                        WikiPage page = readPage();

                        if (page != null) {

                            emitted++;

                            return page;
                        }
                    }
                }

                return null;

            } catch (XMLStreamException e) {

                throw new UncheckedIOException(new IOException("Malformed dump " + dump, e));
            }
        }

        /**
         * Reads the current {@code <page>} element; returns {@code null} for
         * pages that are not articles.
         */
        private WikiPage readPage() throws XMLStreamException {

            String title = null;

            String namespace = "0";

            String wikitext = null;

            boolean redirect = false;

            while (reader.hasNext()) {

                int event = reader.next();

                if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("page")) {

                    break;
                }

                if (event != XMLStreamConstants.START_ELEMENT) {

                    continue;
                }

                switch (reader.getLocalName()) {

                    case "title" -> title = reader.getElementText();

                    case "ns" -> namespace = reader.getElementText().strip();

                    case "redirect" -> redirect = true;

                    case "text" -> wikitext = reader.getElementText();

                    default -> {
                        // Revision metadata, contributor, etc. are not needed.
                    }
                }
            }

            if (title == null || redirect || !namespace.equals("0") || wikitext == null) {

                return null;
            }

            return new WikiPage(title, wikitext, urlPrefix + title.replace(' ', '_'));
        }

        /**
         * {@code https://en.wikipedia.org/wiki/Main_Page} -> {@code https://en.wikipedia.org/wiki/}
         */
        private String prefixOf(String baseUrl) {

            int slash = baseUrl.lastIndexOf('/');

            return slash >= 0 ? baseUrl.substring(0, slash + 1) : DEFAULT_URL_PREFIX;
        }
    }
}
