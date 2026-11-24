package com.searchengine.document.source;

import com.searchengine.document.model.Link;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts MediaWiki markup into plain text suitable for indexing.
 * <p>
 * This is a pragmatic cleaner, not a full wikitext parser: it removes the
 * constructs that would otherwise pollute the index (templates, tables,
 * references, file/category links, HTML tags) and keeps the readable prose,
 * including the visible text of internal and external links.
 */
public final class WikitextCleaner {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern SELF_CLOSING_REF = Pattern.compile("<ref[^>]*/>", Pattern.CASE_INSENSITIVE);

    // Tags whose content is markup or citations rather than prose.
    private static final Pattern DROPPED_ELEMENTS = Pattern.compile(
            "<(ref|math|gallery|timeline|syntaxhighlight|source|score|chem|ce|graph|mapframe"
                    + "|imagemap|templatedata|hiero)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Innermost [[...]] link (no nested brackets inside).
    private static final Pattern INNER_LINK = Pattern.compile("\\[\\[([^\\[\\]]*)\\]\\]");

    private static final Pattern EXTERNAL_LINK = Pattern.compile("\\[(?:https?:|ftp:)?//[^\\s\\]]*\\s*([^\\]]*)\\]");

    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]{0,500}>");

    private static final Pattern EMPHASIS = Pattern.compile("'{2,}");

    private static final Pattern HEADING = Pattern.compile("^=+\\s*(.*?)\\s*=+\\s*$", Pattern.MULTILINE);

    private static final Pattern LIST_MARKER = Pattern.compile("^[*#:;]+\\s*", Pattern.MULTILINE);

    private static final Pattern MAGIC_WORD = Pattern.compile("__[A-Z]+__");

    private static final Pattern ENTITY = Pattern.compile("&(#?[a-zA-Z0-9]+);");

    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    // Interlanguage links such as [[fr:Paris]] or [[zh-yue:...]].
    private static final Pattern LANGUAGE_PREFIX = Pattern.compile("[a-z]{2,3}(-[a-z]+)*");

    private static final Set<String> DROPPED_NAMESPACES = Set.of("file", "image", "category", "media");

    private static final Map<String, String> ENTITIES = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'");

    private static final int MAX_LINKS = 500;

    // Bounds the innermost-first link rewriting; real articles nest at most 2-3 deep.
    private static final int MAX_LINK_PASSES = 6;

    private WikitextCleaner() {
    }

    public static String clean(String wikitext) {

        if (wikitext == null || wikitext.isEmpty()) {

            return "";
        }

        String text = COMMENT.matcher(wikitext).replaceAll("");

        text = SELF_CLOSING_REF.matcher(text).replaceAll("");

        text = DROPPED_ELEMENTS.matcher(text).replaceAll("");

        text = stripNested(text, "{{", "}}");

        text = stripNested(text, "{|", "|}");

        text = rewriteLinks(text);

        text = EXTERNAL_LINK.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));

        text = HTML_TAG.matcher(text).replaceAll("");

        text = EMPHASIS.matcher(text).replaceAll("");

        text = HEADING.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));

        text = LIST_MARKER.matcher(text).replaceAll("");

        text = MAGIC_WORD.matcher(text).replaceAll("");

        text = decodeEntities(text);

        return BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
    }

    /**
     * Remove every (possibly nested) region delimited by {@code open} and
     * {@code close}, e.g. templates or tables. Unbalanced closers are kept.
     */
    static String stripNested(String text, String open, String close) {

        if (!text.contains(open)) {

            return text;
        }

        StringBuilder sb = new StringBuilder(text.length());

        int depth = 0;

        int i = 0;

        while (i < text.length()) {

            if (text.startsWith(open, i)) {

                depth++;

                i += open.length();

            } else if (depth > 0 && text.startsWith(close, i)) {

                depth--;

                i += close.length();

            } else {

                if (depth == 0) {

                    sb.append(text.charAt(i));
                }

                i++;
            }
        }

        return sb.toString();
    }

    /**
     * Replace internal links with their visible text, innermost first so that
     * links inside image captions are resolved before the image itself is
     * dropped.
     */
    private static String rewriteLinks(String text) {

        for (int pass = 0; pass < MAX_LINK_PASSES; pass++) {

            Matcher m = INNER_LINK.matcher(text);

            if (!m.find()) {

                break;
            }

            text = m.reset().replaceAll(match -> Matcher.quoteReplacement(linkText(match.group(1))));
        }

        return text;
    }

    /**
     * Internal article links in raw wikitext, as {@link Link}s to
     * {@code urlPrefix + Target_title} with the visible text as anchor.
     * Namespace (File:, Category:, ...) and interlanguage links are skipped;
     * section anchors ("Page#Section") link to the page.
     */
    public static List<Link> links(String wikitext, String urlPrefix) {

        List<Link> links = new ArrayList<>();

        if (wikitext == null) {

            return links;
        }

        Set<String> seen = new HashSet<>();

        Matcher m = INNER_LINK.matcher(wikitext);

        while (m.find() && links.size() < MAX_LINKS) {

            String inner = m.group(1);

            int pipe = inner.indexOf('|');

            String target = (pipe >= 0 ? inner.substring(0, pipe) : inner).strip();

            int hash = target.indexOf('#');

            if (hash >= 0) {

                target = target.substring(0, hash).strip();
            }

            int colon = target.indexOf(':');

            if (target.isEmpty() || colon >= 0) {

                continue;
            }

            target = target.replace('_', ' ').replaceAll("\\s+", " ");

            // MediaWiki titles always start with a capital letter.
            String title = Character.toUpperCase(target.charAt(0)) + target.substring(1);

            String url = urlPrefix + title.replace(' ', '_');

            if (seen.add(url)) {

                links.add(new Link(url, linkText(inner)));
            }
        }

        return links;
    }

    private static String linkText(String inner) {

        int colon = inner.indexOf(':');

        if (colon > 0) {

            String prefix = inner.substring(0, colon).strip().toLowerCase();

            if (DROPPED_NAMESPACES.contains(prefix) || LANGUAGE_PREFIX.matcher(prefix).matches()) {

                return "";
            }
        }

        int pipe = inner.lastIndexOf('|');

        if (pipe < 0) {

            return inner;
        }

        String label = inner.substring(pipe + 1);

        // "Pipe trick" ([[Target|]]) shows the target.
        return label.isBlank() ? inner.substring(0, pipe) : label;
    }

    private static String decodeEntities(String text) {

        if (text.indexOf('&') < 0) {

            return text;
        }

        return ENTITY.matcher(text).replaceAll(m -> Matcher.quoteReplacement(decodeEntity(m.group(1))));
    }

    private static String decodeEntity(String name) {

        if (name.startsWith("#")) {

            try {

                int codePoint = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));

                return Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : " ";

            } catch (NumberFormatException e) {

                return " ";
            }
        }

        return ENTITIES.getOrDefault(name.toLowerCase(), " ");
    }
}
