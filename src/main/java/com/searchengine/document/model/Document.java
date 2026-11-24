package com.searchengine.document.model;

public class Document {

    private final int id;

    private final String title;

    private final String content;

    private final String path;

    public Document(int id, String content, String path) {

        this(id, "", content, path);
    }

    public Document(int id, String title, String content, String path) {

        this.id = id;

        this.title = title == null ? "" : title;

        this.content = content;

        this.path = path;
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

    public String getContent() {

        return content;
    }

    public String getPath() {

        return path;
    }

    @Override
    public String toString() {

        return "Document{" +

                "id=" + id +

                ", path='" + path + '\'' +

                '}';
    }
}
