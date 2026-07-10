package com.searchengine.document.source;

import com.searchengine.api.DocumentSource;
import com.searchengine.document.model.Document;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads HTML files from a folder and extracts their visible text (plus the page
 * title) using jsoup, so web pages can be indexed like plain-text documents.
 */
public class HtmlDocumentSource implements DocumentSource {

    private final String folderPath;

    public HtmlDocumentSource(String folderPath) {

        this.folderPath = folderPath;
    }

    @Override
    public List<Document> loadDocuments() throws IOException {

        List<Document> documents = new ArrayList<>();

        int docId = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(folderPath))) {

            for (Path path : stream) {

                if (!Files.isRegularFile(path) || !isHtml(path)) {

                    continue;
                }

                String html = Files.readString(path);

                String text = extractText(html, path.toString());

                documents.add(new Document(docId++, text, path.toString()));
            }
        }

        return documents;
    }

    private boolean isHtml(Path path) {

        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        return name.endsWith(".html") || name.endsWith(".htm");
    }

    /**
     * Extract the page title and body text from raw HTML.
     */
    public static String extractText(String html, String baseUri) {

        return textOf(Jsoup.parse(html, baseUri));
    }

    /**
     * Combine a parsed page's title and body text into indexable content. The
     * body text is used (rather than the whole-document text) so the title is
     * not counted twice.
     */
    public static String textOf(org.jsoup.nodes.Document parsed) {

        String body = parsed.body() != null ? parsed.body().text() : "";

        return (parsed.title() + " " + body).strip();
    }
}
