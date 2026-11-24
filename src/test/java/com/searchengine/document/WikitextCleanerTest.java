package com.searchengine.document;

import com.searchengine.document.source.WikitextCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WikitextCleanerTest {

    @Test
    void keepsLinkTextAndDropsMarkup() {

        String wiki = """
                {{Infobox city|name=Paris|population={{formatnum:2100000}}}}
                '''Paris''' is the [[capital city|capital]] of [[France]].<ref>{{cite web|url=x}}</ref>
                == History ==
                * Founded by the [[Parisii]].<ref name="a"/>
                [[File:Paris.jpg|thumb|The [[Eiffel Tower]] at night]]
                See [https://example.org the website].&nbsp;Population &amp; area.
                {| class="wikitable"
                | cell || data
                |}
                <!-- hidden comment -->
                [[Category:Cities]] [[fr:Paris]]
                """;

        String text = WikitextCleaner.clean(wiki);

        assertTrue(text.contains("Paris is the capital of France."), text);

        assertTrue(text.contains("History"), text);

        assertTrue(text.contains("Founded by the Parisii."), text);

        assertTrue(text.contains("See the website."), text);

        assertTrue(text.contains("Population & area."), text);

        for (String junk : new String[]{"Infobox", "cite", "Eiffel", "wikitable", "cell", "hidden",
                "Category", "fr:", "[[", "{{", "'''", "<ref", "=="}) {

            assertFalse(text.contains(junk), "should drop " + junk + ": " + text);
        }
    }

    @Test
    void pipeTrickShowsTarget() {

        assertEquals("see Foo", WikitextCleaner.clean("see [[Foo|]]"));
    }

    @Test
    void nestedTemplatesAreRemovedCompletely() {

        assertEquals("before after", WikitextCleaner.clean("before {{a|{{b|{{c}}}}|d}}after"));
    }

    @Test
    void numericEntitiesAreDecoded() {

        assertEquals("A B", WikitextCleaner.clean("&#65; &#x42;"));
    }
}
