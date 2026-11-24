package com.searchengine.api;

import com.searchengine.document.model.Document;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public interface DocumentSource {

    List<Document> loadDocuments() throws IOException;

    /**
     * Lazily stream documents one at a time. Large sources (e.g. a Wikipedia
     * dump) override this so the corpus never has to fit in memory; small
     * sources simply stream their loaded list.
     * <p>
     * The returned stream may hold open files, so callers should close it
     * (try-with-resources).
     */
    default Stream<Document> stream() throws IOException {

        return loadDocuments().stream();
    }
}
