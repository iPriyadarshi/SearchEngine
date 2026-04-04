package com.searchengine.api;

import com.searchengine.document.model.Document;

import java.io.IOException;
import java.util.List;

public interface DocumentSource {

    List<Document> loadDocuments() throws IOException;
}