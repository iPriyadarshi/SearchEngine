package com.searchengine.document.source;

import com.searchengine.api.DocumentSource;
import com.searchengine.document.model.Document;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// Loads .txt files from folder.
public class FolderDocumentSource implements DocumentSource {

    private final String folderPath;

    public FolderDocumentSource(String folderPath) {

        this.folderPath = folderPath;
    }

    @Override
    public List<Document> loadDocuments() throws IOException {

        List<Document> documents = new ArrayList<>();

        int docId = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(folderPath))) {

            for (Path path : stream) {

                if (!Files.isRegularFile(path)) {

                    continue;
                }

                String content = Files.readString(path);

                Document document = new Document(docId++, content, path.toString());

                documents.add(document);
            }
        }

        return documents;
    }
}