package com.searchengine.engine;

import com.searchengine.document.model.Document;
import com.searchengine.query.result.RankedDocument;
import com.searchengine.query.result.SearchResult;

/**
 * Renders a {@link SearchResult} into human readable text with document paths
 * and short content snippets.
 */
public class ResultFormatter {

    private static final int SNIPPET_LENGTH = 120;

    private final SearchEngine engine;

    public ResultFormatter(SearchEngine engine) {

        this.engine = engine;
    }

    public String format(SearchResult result) {

        if (result.getResults().isEmpty()) {

            return "No matching documents.";
        }

        StringBuilder sb = new StringBuilder();

        int rank = 1;

        for (RankedDocument doc : result.getResults()) {

            Document source = engine.getDocument(doc.getDocId());

            String path = source != null ? source.getPath() : "?";

            sb.append(String.format("%2d. doc=%d  score=%.4f  %s%n",
                    rank++, doc.getDocId(), doc.getScore(), path));

            if (source != null) {

                sb.append("    ").append(snippet(source.getContent())).append(System.lineSeparator());
            }
        }

        return sb.toString().stripTrailing();
    }

    public static String snippet(String content) {

        if (content == null) {

            return "";
        }

        String flat = content.replaceAll("\\s+", " ").strip();

        if (flat.length() <= SNIPPET_LENGTH) {

            return flat;
        }

        return flat.substring(0, SNIPPET_LENGTH) + "...";
    }
}
