package com.searchengine.document.model;

import java.util.List;

public class Document {

    private final int id;

    private final String title;

    private final String description;

    private final String content;

    private final String path;

    private final List<Link> links;

    public Document(int id, String content, String path) {

        this(id, "", content, path);
    }

    public Document(int id, String title, String content, String path) {

        this(id, title, "", content, path, List.of());
    }

    /**
     * A web page: title, meta description, body text, URL and outgoing links.
     */
    public Document(int id, String title, String description, String content, String path, List<Link> links) {

        this.id = id;

        this.title = title == null ? "" : title;

        this.description = description == null ? "" : description;

        this.content = content;

        this.path = path;

        this.links = links == null ? List.of() : List.copyOf(links);
    }

    public int getId() {

        return id;
    }

    /**
     * Human readable title, or an empty string when the source has none.
     */
    public String getTitle() {

        return title;
    }

    /**
     * Author-written summary (e.g. an HTML meta description), or an empty string.
     */
    public String getDescription() {

        return description;
    }

    public String getContent() {

        return content;
    }

    public String getPath() {

        return path;
    }

    /**
     * Outgoing links, used to build the link graph and anchor text.
     */
    public List<Link> getLinks() {

        return links;
    }

    @Override
    public String toString() {

        return "Document{" +

                "id=" + id +

                ", path='" + path + '\'' +

                '}';
    }
}
