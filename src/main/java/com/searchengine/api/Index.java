package com.searchengine.api;

import com.searchengine.document.model.Document;
import com.searchengine.index.model.PostingList;

import java.util.List;

public interface Index {

    void addDocument(Document doc, List<String> tokens);

    PostingList getPostingList(String term);

    int getDocumentFrequency(String term);

    int getTotalDocuments();
}