package com.searchengine.engine;

import com.searchengine.api.Index;
import com.searchengine.api.Parser;
import com.searchengine.api.Ranker;
import com.searchengine.document.model.Document;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level facade that ties the whole pipeline together.
 * <p>
 * source/documents -> parser -> index -> ranker -> results
 * <p>
 * The engine keeps the original documents so results can be rendered with
 * snippets, and it parses queries through the SAME parser pipeline used for
 * documents so that normalization (lowercasing, stopwords, stemming, ...) is
 * identical on both sides.
 */
public class SearchEngine {

    private final Parser parser;

    private final Index index;

    private final Map<Integer, Document> documents = new ConcurrentHashMap<>();

    public SearchEngine(Parser parser, Index index) {

        this.parser = parser;

        this.index = index;
    }

    /**
     * Index a batch of documents.
     */
    public void index(Collection<Document> docs) {

        for (Document doc : docs) {

            indexDocument(doc);
        }
    }

    /**
     * Index a single document. Supports incremental indexing: documents can be
     * added at any time after the initial batch.
     */
    public void indexDocument(Document doc) {

        List<String> tokens = parser.parse(doc);

        index.addDocument(doc, tokens);

        documents.put(doc.getId(), doc);
    }

    /**
     * Normalize a raw query string using the same pipeline as documents.
     */
    public List<String> analyzeQuery(String queryString) {

        Document queryDoc = new Document(-1, queryString, "<query>");

        return parser.parse(queryDoc);
    }

    /**
     * Run a ranked keyword search with the supplied ranking strategy.
     */
    public SearchResult search(String queryString, Ranker ranker) {

        List<String> terms = analyzeQuery(queryString);

        List<RankedDocument> ranked = ranker.rank(terms);

        return new SearchResult(ranked);
    }

    public Document getDocument(int docId) {

        return documents.get(docId);
    }

    public int getDocumentCount() {

        return documents.size();
    }

    public Index getIndex() {

        return index;
    }
}
