package com.searchengine.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SnippetsTest {

    @Test
    void centresOnFirstQueryWord() {

        String text = "Lorem ipsum ".repeat(30) + "the Eiffel Tower is in Paris " + "dolor sit ".repeat(30);

        String snippet = Snippets.around(text, "eiffel tower");

        assertTrue(snippet.startsWith("..."), snippet);

        assertTrue(snippet.contains("Eiffel Tower"), snippet);

        assertTrue(snippet.length() <= Snippets.LENGTH + 6, snippet);
    }

    @Test
    void fallsBackToTheStartAndCollapsesWhitespace() {

        assertEquals("short text here", Snippets.around("short\n\ntext   here", "unrelated"));
    }
}
