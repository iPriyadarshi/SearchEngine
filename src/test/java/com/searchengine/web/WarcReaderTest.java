package com.searchengine.web;

import com.searchengine.document.model.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WarcReaderTest {

    private static final String ENGLISH_HTML = "<html lang=en><head><title>Tea</title>"
            + "<meta name=description content='All about tea.'></head><body><p>"
            + "Tea is an aromatic beverage prepared by pouring hot water over the cured leaves of the tea plant. "
                    .repeat(4)
            + "<a href='/green'>green tea</a></p></body></html>";

    private static byte[] record(String type, String uri, String payload) {

        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        String header = "WARC/1.0\r\nWARC-Type: " + type + "\r\n"
                + (uri == null ? "" : "WARC-Target-URI: " + uri + "\r\n")
                + "Content-Length: " + body.length + "\r\n\r\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));

        out.writeBytes(body);

        out.writeBytes("\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        return out.toByteArray();
    }

    private static String http(int status, String contentType, String body) {

        return "HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType + "\r\nServer: test\r\n\r\n" + body;
    }

    /**
     * Each record gzipped as its own member, like Common Crawl files.
     */
    private static void writeWarcGz(Path file, List<byte[]> records) throws Exception {

        try (OutputStream out = Files.newOutputStream(file)) {

            for (byte[] record : records) {

                ByteArrayOutputStream member = new ByteArrayOutputStream();

                try (GZIPOutputStream gz = new GZIPOutputStream(member)) {

                    gz.write(record);
                }

                out.write(member.toByteArray());
            }
        }
    }

    @Test
    void readsOnlySuccessfulHtmlResponsesAcrossGzipMembers(@TempDir Path dir) throws Exception {

        Path file = dir.resolve("sample.warc.gz");

        writeWarcGz(file, List.of(
                record("warcinfo", null, "software: test"),
                record("request", "https://tea.example/", "GET / HTTP/1.1\r\n\r\n"),
                record("response", "https://tea.example/", http(200, "text/html; charset=UTF-8", ENGLISH_HTML)),
                record("response", "https://tea.example/missing", http(404, "text/html", "<p>nope</p>")),
                record("response", "https://tea.example/logo.png", http(200, "image/png", "PNG")),
                record("metadata", "https://tea.example/", "fetchTimeMs: 12"),
                record("response", "https://other.example/", http(200, "text/html", "<p>second</p>"))));

        List<WarcReader.Record> records = new ArrayList<>();

        try (WarcReader reader = new WarcReader(Files.newInputStream(file), true)) {

            reader.forEachRemaining(records::add);
        }

        assertEquals(2, records.size());

        assertEquals("https://tea.example/", records.get(0).url());

        assertEquals("utf-8", records.get(0).charset());

        assertTrue(new String(records.get(0).html(), StandardCharsets.UTF_8).startsWith("<html lang=en>"));

        assertEquals("https://other.example/", records.get(1).url());
    }

    @Test
    void toDocumentExtractsAndFilters(@TempDir Path dir) throws Exception {

        Document tea = CommonCrawlSource.toDocument(new WarcReader.Record(
                "https://tea.example/", "utf-8", ENGLISH_HTML.getBytes(StandardCharsets.UTF_8)));

        assertNotNull(tea);

        assertEquals("Tea", tea.getTitle());

        assertEquals("All about tea.", tea.getDescription());

        assertEquals("https://tea.example/green", tea.getLinks().getFirst().url());

        assertNull(CommonCrawlSource.toDocument(new WarcReader.Record(
                "https://short.example/", null, "<html><body><p>Too short</p></body></html>".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void readsMultipleFilesThroughCommonCrawlSource(@TempDir Path dir) throws Exception {

        Path a = dir.resolve("a.warc.gz");

        Path b = dir.resolve("b.warc.gz");

        writeWarcGz(a, List.of(record("response", "https://a.example/", http(200, "text/html", ENGLISH_HTML))));

        writeWarcGz(b, List.of(record("response", "https://b.example/", http(200, "text/html", ENGLISH_HTML))));

        List<String> urls = new ArrayList<>();

        CommonCrawlSource source = new CommonCrawlSource(List.of(a.toString(), dir.resolve("missing.warc.gz").toString(),
                b.toString()), message -> { });

        try (CommonCrawlSource.Records records = source.records()) {

            records.forEachRemaining(r -> urls.add(r.url()));
        }

        assertEquals(List.of("https://a.example/", "https://b.example/"), urls);
    }
}
