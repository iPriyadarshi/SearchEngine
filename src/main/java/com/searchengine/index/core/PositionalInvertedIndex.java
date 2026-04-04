package com.searchengine.index.core;

import com.searchengine.api.Index;
import com.searchengine.document.model.Document;
import com.searchengine.index.model.PostingList;

import java.util.List;

public class PositionalInvertedIndex implements Index {

    @Override
    public void addDocument(Document doc, List<String> tokens) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public PostingList getPostingList(String term) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public int getDocumentFrequency(String term) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public int getTotalDocuments() {

        throw new UnsupportedOperationException("Not implemented yet");
    }
}