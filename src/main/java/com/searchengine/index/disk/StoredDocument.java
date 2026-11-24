package com.searchengine.index.disk;

/**
 * Display fields kept for each document so results can be shown without the
 * original source: title, path/URL, and the leading text used as a snippet.
 */
public record StoredDocument(int docId, String title, String path, String text) {
}
