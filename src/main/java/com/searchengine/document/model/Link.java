package com.searchengine.document.model;

/**
 * An outgoing hyperlink: the (normalized) target URL and the visible anchor
 * text. Anchor text is how other pages describe the target, which makes it
 * one of the strongest relevance signals on the web.
 */
public record Link(String url, String anchor) {
}
