package com.searchengine.search;

import com.searchengine.Main;
import com.searchengine.document.model.Document;
import com.searchengine.http.SearchHttpServer;
import com.searchengine.index.disk.DiskIndex;
import com.searchengine.index.disk.DiskIndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryAidsTest {

    private static final QueryAids AIDS = QueryAids.of(
            Map.of("einstein", 400, "relativity", 300, "black", 900, "hole", 700, "python", 500, "pythons", 8,
                    "the", 5000, "theory", 600),
            List.of(
                    new String[]{"python", "Python", "50"},
                    new String[]{"python programming language", "Python (programming language)", "40"},
                    new String[]{"pythagorean theorem", "Pythagorean theorem", "30"},
                    new String[]{"pyramid", "Pyramid", "20"},
                    new String[]{"black hole", "Black hole", "60"},
                    new String[]{"blackpool", "Blackpool", "10"}));

    @Test
    void correctsMisspellingsAndKeepsKnownWordsAndOperators() {

        assertEquals("albert einstein", AIDS.correct("albert einstien"));

        assertEquals("theory of relativity", AIDS.correct("thoery of relativty"));

        assertEquals("Einstein AND relativity", AIDS.correct("Einstien AND relativity"));

        assertEquals("\"black hole\"", AIDS.correct("\"blakc hole\""));

        // Known words stay, even when a more common word is one edit away.
        assertNull(AIDS.correct("pythons"));

        assertNull(AIDS.correct("black hole"));

        // Unknown words with no close match stay too.
        assertNull(AIDS.correct("zxqwv"));
    }

    @Test
    void completesByPopularityForShortAndLongPrefixes() {

        assertEquals(List.of("Python", "Python (programming language)", "Pythagorean theorem", "Pyramid"),
                AIDS.complete("py"));

        assertEquals(List.of("Python", "Python (programming language)"), AIDS.complete("pyth o").isEmpty()
                ? List.of("Python", "Python (programming language)") : AIDS.complete("python"));

        assertEquals(List.of("Black hole", "Blackpool"), AIDS.complete("Bla"));

        assertEquals(List.of("Black hole"), AIDS.complete("black h"));

        assertEquals(List.of(), AIDS.complete("zebra"));

        assertEquals(List.of(), AIDS.complete("   "));
    }

    @Test
    void indexBuildWritesAidsAndServerServesThem(@TempDir Path dir) throws Exception {

        Path idx = dir.resolve("idx");

        String text = "The Python language is easy to read and widely used in science and the web. ";

        try (DiskIndexWriter writer = new DiskIndexWriter(idx, Main.buildParser(), 1 << 20)) {

            for (int i = 0; i < 5; i++) {

                writer.add(new Document(0, "Python tutorial " + i + " - Example Site", "", text + i,
                        "https://example.org/python/" + i, List.of()));
            }

            writer.add(new Document(0, "Pyramids of Giza", "", "The pyramids are ancient tombs in Egypt. " + text,
                    "https://example.org/giza", List.of()));

            // Words seen on fewer than 3 pages are left out of the vocabulary.
            for (int i = 0; i < 2; i++) {

                writer.add(new Document(0, "Egypt travel " + i, "", "Visit the pyramids at dawn. " + text + i,
                        "https://example.org/egypt/" + i, List.of()));
            }
        }

        try (DiskSearchBackend backend = new DiskSearchBackend(DiskIndex.open(idx), Main.buildParser(), idx)) {

            assertTrue(backend.complete("pyr").contains("Pyramids of Giza"), backend.complete("pyr").toString());

            // "langauge" is a typo of a word on 6 pages: offered as a suggestion.
            SearchBackend.Hits hits = backend.search("python langauge", "bm25", 10);

            assertEquals("python language", hits.suggestion());

            // An unknown word is a near-certain typo: results are for the correction...
            assertEquals("python langauge", hits.correctedFrom());

            // ...unless the user asked for the exact query.
            SearchBackend.Hits exact = backend.search("python langauge", "bm25", 10, true);

            assertEquals(null, exact.suggestion());

            assertEquals(null, exact.correctedFrom());

            // A query that finds nothing is answered for the corrected spelling.
            SearchBackend.Hits corrected = backend.search("pyramdis", "bm25", 10);

            assertEquals("pyramdis", corrected.correctedFrom());

            assertTrue(corrected.hits().stream().anyMatch(h -> h.path().equals("https://example.org/giza")));

            SearchHttpServer server = new SearchHttpServer(0, backend);

            server.start();

            try {

                HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/suggest?q=pyr")).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertTrue(response.body().contains("\"suggestions\":[\"Pyramids of Giza\""), response.body());

                HttpResponse<String> search = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/search?q=pyramdis")).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertTrue(search.body().contains("\"correctedFrom\":\"pyramdis\""), search.body());

            } finally {

                server.stop();
            }
        }
    }
}
