package com.searchengine.document.model;

public class Document {

    private final int id;

    private final String content;

    private final String path;

    public Document(int id, String content, String path) {

        this.id = id;

        this.content = content;

        this.path = path;
    }

    public int getId() {

        return id;
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