package com.searchengine.api;

import com.searchengine.document.model.Document;

import java.util.List;

public interface Parser {

    List<String> parse(Document document);
}