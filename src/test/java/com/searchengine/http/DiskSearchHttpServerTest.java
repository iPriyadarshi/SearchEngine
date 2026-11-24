package com.searchengine.http;

import com.searchengine.Main;
import com.searchengine.document.model.Document;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import com.searchengine.search.DiskSearchBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP API served from an on-disk index with the default analysis chain.
 */
public class DiskSearchHttpServerTest {

    @TempDir
    Path dir;

    private SearchHttpServer server;

    private DiskSearchBackend backend;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {

        Path indexDir = dir.resolve("idx");

        try (DiskIndexWriter writer = new DiskIndexWriter(indexDir, Main.buildParser(), 1 << 20)) {

            writer.add(new Document(0, "Search engine", "Search engine\n\nA search engine ranks indexed documents.",
                    "https://example.org/wiki/Search_engine"));

            writer.add(new Document(1, "Inverted index", "Inverted index\n\nAn inverted index maps terms to documents.",
                    "https://example.org/wiki/Inverted_index"));

            writer.add(new Document(2, "Cooking", "Cooking\n\nRecipes for bread and soup.",
                    "https://example.org/wiki/Cooking"));
        }

        backend = new DiskSearchBackend(DiskIndex.open(indexDir), Main.buildParser(), indexDir);

        server = new SearchHttpServer(0, backend);

        server.start();
    }

    @AfterEach
    void tearDown() {

        server.stop();

        backend.close();
    }

    private HttpResponse<String> get(String path) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + path))
                .GET()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rankedSearchDefaultsToBm25AndReturnsTitles() throws Exception {

        HttpResponse<String> response = get("/search?q=indexing");

        assertEquals(200, response.statusCode());

        String body = response.body();

        // "indexing" stems to "index", which matches "indexed" and "index".
        assertTrue(body.contains("\"mode\":\"bm25\""), body);

        assertTrue(body.contains("\"title\":\"Inverted index\""), body);

        assertTrue(body.contains("\"title\":\"Search engine\""), body);

        assertFalse(body.contains("Cooking"), body);

        assertTrue(body.contains("\"tookMs\":"), body);
    }

    @Test
    void phraseAndBooleanEndpointsUseTheDiskIndex() throws Exception {

        String phrase = get("/phrase?q=%22inverted%20index%22").body();

        assertTrue(phrase.contains("\"docId\":1") && !phrase.contains("\"docId\":0"), phrase);

        String bool = get("/bool?q=documents%20AND%20NOT%20inverted").body();

        assertTrue(bool.contains("\"docId\":0") && !bool.contains("\"docId\":1"), bool);

        assertTrue(bool.contains("\"total\":1"), bool);
    }

    @Test
    void badInputIsA400AndLimitIsCapped() throws Exception {

        assertEquals(400, get("/search?q=x&ranker=nope").statusCode());

        assertEquals(400, get("/bool?q=(a%20AND").statusCode());

        assertEquals(200, get("/search?q=documents&limit=100000").statusCode());

        assertTrue(get("/health").body().contains("\"documents\":3"));
    }

    @Test
    void offsetPagesThroughResults() throws Exception {

        String all = get("/search?q=documents&limit=10").body();

        String second = get("/search?q=documents&limit=1&offset=1").body();

        assertTrue(all.contains("\"count\":2"), all);

        assertTrue(second.contains("\"count\":1") && second.contains("\"offset\":1"), second);

        // The second page holds the second hit of the full list.
        int secondDoc = all.indexOf("\"docId\":", all.indexOf("\"docId\":") + 1);

        String expected = all.substring(secondDoc, all.indexOf(',', secondDoc));

        assertTrue(second.contains(expected), expected + " in " + second);

        assertTrue(get("/search?q=documents&offset=999").body().contains("\"offset\":490"));
    }
}
