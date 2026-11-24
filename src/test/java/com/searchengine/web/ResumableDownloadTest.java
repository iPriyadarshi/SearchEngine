package com.searchengine.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResumableDownloadTest {

    @Test
    void resumesWithRangeRequestsAfterADroppedConnection(@TempDir Path dir) throws Exception {

        byte[] file = new byte[300_000];

        new Random(1).nextBytes(file);

        List<String> ranges = Collections.synchronizedList(new ArrayList<>());

        AtomicBoolean dropNext = new AtomicBoolean(true);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);

        // Serves byte ranges; the first response breaks off a third of the way in.
        HttpHandler handler = exchange -> {

            String range = exchange.getRequestHeaders().getFirst("Range");

            ranges.add(String.valueOf(range));

            int from = range == null ? 0 : Integer.parseInt(range.replaceAll("bytes=(\\d+)-", "$1"));

            if (range != null) {

                exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + (file.length - 1) + "/" + file.length);
            }

            exchange.sendResponseHeaders(range == null ? 200 : 206, file.length - from);

            OutputStream out = exchange.getResponseBody();

            if (dropNext.getAndSet(false)) {

                out.write(file, from, file.length / 3 - from);

                out.flush();

                // Abort the connection without completing the declared length.
                exchange.getHttpContext().getServer().stop(0);

                return;
            }

            out.write(file, from, file.length - from);

            out.close();
        };

        server.createContext("/big.warc.gz", handler);

        server.start();

        int port = server.getAddress().getPort();

        // Bring the server back on the same port, as after a network blip.
        Thread restart = new Thread(() -> {

            try {

                while (ranges.isEmpty()) {

                    Thread.sleep(10);
                }

                Thread.sleep(300);

                HttpServer again = HttpServer.create(new InetSocketAddress(port), 0);

                again.createContext("/big.warc.gz", handler);

                again.start();

            } catch (Exception e) {

                throw new RuntimeException(e);
            }
        });

        restart.setDaemon(true);

        restart.start();

        Path target = dir.resolve("big.warc.gz");

        ResumableDownload.fetch("http://localhost:" + port + "/big.warc.gz", target);

        assertArrayEquals(file, Files.readAllBytes(target));

        assertEquals("null", ranges.getFirst());

        assertTrue(ranges.size() >= 2, "should have retried: " + ranges);

        assertTrue(ranges.stream().skip(1).anyMatch(r -> r.startsWith("bytes=") && !r.equals("bytes=0-")),
                "the retry must resume, not restart: " + ranges);
    }
}
