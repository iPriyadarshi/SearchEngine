package com.searchengine.web;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Streams HTML responses out of a WARC file (the archive format Common Crawl
 * publishes), one record at a time.
 * <p>
 * A {@code .warc.gz} file is a series of gzip members, one per record. They
 * are read with commons-compress's {@link GzipCompressorInputStream}: the JDK's
 * {@code GZIPInputStream} decides whether another member follows by calling
 * {@code available()}, which is often 0 on a network stream, so it silently
 * stops after the first few records. Only {@code response} records
 * with HTTP status 200 and an HTML content type are returned; requests,
 * metadata, redirects, errors and non-HTML payloads are skipped without
 * buffering them. Payloads above {@value #MAX_PAYLOAD_BYTES} bytes are skipped.
 */
public final class WarcReader implements Iterator<WarcReader.Record>, Closeable {

    static final int MAX_PAYLOAD_BYTES = 2 << 20;

    /**
     * An archived HTML page.
     *
     * @param charset charset named in the HTTP Content-Type, or {@code null}
     */
    public record Record(String url, String charset, byte[] html) {
    }

    private final InputStream in;

    private Record next;

    private boolean done;

    public WarcReader(InputStream raw, boolean gzipped) throws IOException {

        InputStream buffered = new BufferedInputStream(raw, 1 << 16);

        this.in = new BufferedInputStream(gzipped ? new GzipCompressorInputStream(buffered, true) : buffered, 1 << 16);
    }

    @Override
    public boolean hasNext() {

        if (next == null && !done) {

            try {

                next = readNextHtml();

            } catch (IOException e) {

                throw new UncheckedIOException(e);
            }

            done = next == null;
        }

        return next != null;
    }

    @Override
    public Record next() {

        if (!hasNext()) {

            throw new NoSuchElementException();
        }

        Record record = next;

        next = null;

        return record;
    }

    private Record readNextHtml() throws IOException {

        while (true) {

            Map<String, String> headers = readWarcHeaders();

            if (headers == null) {

                return null;
            }

            long length = Long.parseLong(headers.getOrDefault("content-length", "0").strip());

            boolean candidate = "response".equalsIgnoreCase(headers.get("warc-type"))
                    && headers.containsKey("warc-target-uri")
                    && length <= MAX_PAYLOAD_BYTES;

            if (!candidate) {

                skipFully(length);

                continue;
            }

            byte[] payload = in.readNBytes((int) length);

            if (payload.length < length) {

                return null;
            }

            Record record = parseHttp(headers.get("warc-target-uri").strip(), payload);

            if (record != null) {

                return record;
            }
        }
    }

    /**
     * Reads the "WARC/1.x" line and header block; {@code null} at end of input.
     */
    private Map<String, String> readWarcHeaders() throws IOException {

        String line;

        do {

            line = readLine();

            if (line == null) {

                return null;
            }

        } while (!line.startsWith("WARC/"));

        Map<String, String> headers = new HashMap<>();

        while ((line = readLine()) != null && !line.isEmpty()) {

            int colon = line.indexOf(':');

            if (colon > 0) {

                headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
        }

        return headers;
    }

    /**
     * Splits an HTTP response into status, headers and body; returns a record
     * only for 200 responses with an HTML content type.
     */
    static Record parseHttp(String url, byte[] payload) {

        int headerEnd = indexOf(payload, new byte[]{'\r', '\n', '\r', '\n'});

        int separator = 4;

        if (headerEnd < 0) {

            headerEnd = indexOf(payload, new byte[]{'\n', '\n'});

            separator = 2;
        }

        if (headerEnd < 0) {

            return null;
        }

        String head = new String(payload, 0, headerEnd, StandardCharsets.ISO_8859_1);

        String[] lines = head.split("\r?\n");

        String[] status = lines[0].split(" ");

        if (status.length < 2 || !status[1].equals("200")) {

            return null;
        }

        String contentType = "";

        for (int i = 1; i < lines.length; i++) {

            if (lines[i].regionMatches(true, 0, "content-type:", 0, 13)) {

                contentType = lines[i].substring(13).strip().toLowerCase(Locale.ROOT);
            }
        }

        if (!contentType.contains("html")) {

            return null;
        }

        String charset = null;

        int at = contentType.indexOf("charset=");

        if (at >= 0) {

            charset = contentType.substring(at + 8).replaceAll("[\"';].*", "").strip();
        }

        byte[] body = java.util.Arrays.copyOfRange(payload, headerEnd + separator, payload.length);

        return new Record(url, charset == null || charset.isEmpty() ? null : charset, body);
    }

    private static int indexOf(byte[] data, byte[] pattern) {

        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {

            for (int j = 0; j < pattern.length; j++) {

                if (data[i + j] != pattern[j]) {

                    continue outer;
                }
            }

            return i;
        }

        return -1;
    }

    private String readLine() throws IOException {

        ByteArrayOutputStream line = new ByteArrayOutputStream(128);

        int b;

        while ((b = in.read()) != -1) {

            if (b == '\n') {

                break;
            }

            line.write(b);
        }

        if (b == -1 && line.size() == 0) {

            return null;
        }

        String text = line.toString(StandardCharsets.UTF_8);

        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    private void skipFully(long bytes) throws IOException {

        while (bytes > 0) {

            long skipped = in.skip(bytes);

            if (skipped <= 0) {

                if (in.read() == -1) {

                    throw new EOFException("Truncated WARC record");
                }

                skipped = 1;
            }

            bytes -= skipped;
        }
    }

    @Override
    public void close() throws IOException {

        in.close();
    }
}
