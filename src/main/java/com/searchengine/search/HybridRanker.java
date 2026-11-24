package com.searchengine.search;

import com.searchengine.query.disk.TopDocs;
import com.searchengine.query.disk.TopDocs.ScoredDoc;
import com.searchengine.semantic.SemanticSearcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines keyword ranking ({@link WebRanker}) with semantic retrieval
 * ({@link SemanticSearcher}) by Reciprocal Rank Fusion:
 * <pre>
 * score(d) = 1 / (60 + keywordRank(d)) + w / (60 + semanticRank(d))
 * </pre>
 * RRF uses only ranks, so the two very different score scales need no
 * calibration. Keyword search finds exact names and rare terms; semantic
 * search finds pages that answer the question in other words ("how tall is
 * the eiffel tower" → "330 metres"). Each contributes its top
 * {@value #DEPTH} candidates.
 */
public final class HybridRanker {

    static final int DEPTH = 100;

    static final int RRF_K = 60;

    private final WebRanker keyword;

    private final SemanticSearcher semantic;

    private final double semanticWeight;

    public HybridRanker(WebRanker keyword, SemanticSearcher semantic, double semanticWeight) {

        this.keyword = keyword;

        this.semantic = semantic;

        this.semanticWeight = semanticWeight;
    }

    public TopDocs search(String query, int k) {

        TopDocs lexical = keyword.search(query, Math.max(k, DEPTH));

        if (semantic == null || semanticWeight <= 0) {

            return new TopDocs(lexical.hits().subList(0, Math.min(k, lexical.hits().size())),
                    lexical.totalHits(), lexical.scored());
        }

        TopDocs meaning;

        try {

            meaning = semantic.search(query, Math.max(k, DEPTH));

        } catch (IOException e) {

            throw new UncheckedIOException(e);
        }

        Map<Integer, Double> fused = new HashMap<>();

        Map<Integer, Integer> keywordRank = new HashMap<>();

        for (int i = 0; i < lexical.hits().size(); i++) {

            int doc = lexical.hits().get(i).docId();

            fused.merge(doc, 1.0 / (RRF_K + i + 1), Double::sum);

            keywordRank.put(doc, i);
        }

        for (int i = 0; i < meaning.hits().size(); i++) {

            fused.merge(meaning.hits().get(i).docId(), semanticWeight / (RRF_K + i + 1), Double::sum);
        }

        List<ScoredDoc> ranked = new ArrayList<>();

        fused.forEach((doc, score) -> ranked.add(new ScoredDoc(doc, score)));

        // Ties go to the better keyword rank, then the lower doc id.
        ranked.sort(Comparator.comparingDouble(ScoredDoc::score).reversed()
                .thenComparingInt(d -> keywordRank.getOrDefault(d.docId(), Integer.MAX_VALUE))
                .thenComparingInt(ScoredDoc::docId));

        return new TopDocs(ranked.subList(0, Math.min(k, ranked.size())), -1, lexical.scored() + meaning.scored());
    }
}
