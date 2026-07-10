package com.searchengine.parser.stemmer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PorterStemmerTest {

    private final PorterStemmer stemmer = new PorterStemmer();

    @Test
    void shouldStripPlurals() {

        assertEquals("caress", stemmer.stem("caresses"));
        assertEquals("poni", stemmer.stem("ponies"));
        assertEquals("cat", stemmer.stem("cats"));
    }

    @Test
    void shouldHandlePastAndGerund() {

        assertEquals("agre", stemmer.stem("agreed"));
        assertEquals("hop", stemmer.stem("hopping"));
        assertEquals("feed", stemmer.stem("feed"));
    }

    @Test
    void shouldCollapseDerivationalVariants() {

        assertEquals("ration", stemmer.stem("rational"));
        assertEquals("valid", stemmer.stem("validate"));
        assertEquals("connect", stemmer.stem("connection"));
        assertEquals("connect", stemmer.stem("connected"));
        assertEquals("connect", stemmer.stem("connecting"));
    }

    @Test
    void shouldLeaveShortWordsUntouched() {

        assertEquals("of", stemmer.stem("of"));
        assertEquals("a", stemmer.stem("a"));
    }
}
