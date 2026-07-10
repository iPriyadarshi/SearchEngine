package com.searchengine.api;

import java.util.Set;

/**
 * Exposes the set of all indexed document ids. Needed by boolean queries to
 * evaluate NOT against the full corpus (the "universe").
 */
public interface DocumentIdProvider {

    Set<Integer> documentIds();
}
