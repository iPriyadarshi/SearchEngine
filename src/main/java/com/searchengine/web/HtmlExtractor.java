package com.searchengine.web;

import com.searchengine.document.model.Document;
import com.searchengine.document.model.Link;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a parsed HTML page into a search {@link Document}.
 * <p>
 * Extracts the title (falling back to og:title and the first h1), meta
 * description, canonical URL, language, outgoing links with anchor text, and
 * the main text. Page chrome that repeats across a site (navigation, headers,
 * footers, sidebars, forms, scripts) is removed so it does not pollute every
 * page's text. Pages that ask not to be indexed ({@code noindex}) are skipped,
 * and {@code nofollow} links are not recorded.
 */
public final class HtmlExtractor {

    static final int MAX_LINKS = 300;

    static final int MAX_ANCHOR_CHARS = 120;

    private static final String BOILERPLATE =
            "script, style, noscript, template, iframe, svg, canvas, nav, header, footer, aside, form, "
                    + "button, select, [role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true], "
                    + "[hidden], .cookie-banner, #cookie-banner";

    private HtmlExtractor() {
    }

    /**
     * The page's extracted content, before any quality filtering.
     */
    public record Page(String url, String title, String description, String text, String language,
                       List<Link> links) {

        public Document toDocument() {

            return new Document(0, title, description, text, url, links);
        }
    }

    /**
     * @param fetchedUrl the URL the page was fetched from
     * @return the extracted page, or {@code null} if it must not be indexed
     */
    public static Page extract(org.jsoup.nodes.Document html, String fetchedUrl) {

        String url = UrlNormalizer.normalize(fetchedUrl);

        if (url == null) {

            return null;
        }

        String robots = meta(html, "robots") + " " + meta(html, "googlebot");

        robots = robots.toLowerCase(Locale.ROOT);

        if (robots.contains("noindex") || robots.contains("none")) {

            return null;
        }

        boolean followLinks = !robots.contains("nofollow");

        String canonical = canonicalUrl(html, url);

        String title = clean(html.title());

        if (title.isEmpty()) {

            title = clean(property(html, "og:title"));
        }

        if (title.isEmpty()) {

            Element h1 = html.selectFirst("h1");

            title = h1 == null ? "" : clean(h1.text());
        }

        String description = clean(meta(html, "description"));

        if (description.isEmpty()) {

            description = clean(property(html, "og:description"));
        }

        String language = html.selectFirst("html") == null ? "" : html.selectFirst("html").attr("lang").strip();

        List<Link> links = followLinks ? links(html, canonical) : List.of();

        String text = mainText(html);

        return new Page(canonical, title, description, text, language, links);
    }

    private static String canonicalUrl(org.jsoup.nodes.Document html, String url) {

        Element link = html.selectFirst("link[rel=canonical][href]");

        if (link == null) {

            return url;
        }

        String canonical = UrlNormalizer.normalize(link.absUrl("href"));

        // Only trust a canonical on the same host; cross-host canonicals are often spam.
        return canonical != null && UrlNormalizer.host(canonical).equals(UrlNormalizer.host(url)) ? canonical : url;
    }

    private static List<Link> links(org.jsoup.nodes.Document html, String pageUrl) {

        List<Link> links = new ArrayList<>();

        Set<String> seen = new LinkedHashSet<>();

        for (Element a : html.select("a[href]")) {

            if (a.attr("rel").toLowerCase(Locale.ROOT).contains("nofollow")) {

                continue;
            }

            String target = UrlNormalizer.normalize(a.absUrl("href"));

            if (target == null || target.equals(pageUrl) || !seen.add(target)) {

                continue;
            }

            String anchor = clean(a.text());

            if (anchor.isEmpty()) {

                Element img = a.selectFirst("img[alt]");

                anchor = img == null ? "" : clean(img.attr("alt"));
            }

            if (anchor.length() > MAX_ANCHOR_CHARS) {

                anchor = anchor.substring(0, MAX_ANCHOR_CHARS);
            }

            links.add(new Link(target, anchor));

            if (links.size() == MAX_LINKS) {

                break;
            }
        }

        return links;
    }

    /**
     * Body text with site chrome removed. Prefers {@code <main>} or
     * {@code <article>} when the page marks its main content.
     */
    static String mainText(org.jsoup.nodes.Document html) {

        Element body = html.body();

        if (body == null) {

            return "";
        }

        Element root = body.clone();

        Element main = root.selectFirst("main, [role=main], article");

        if (main != null && main.text().length() > 200) {

            root = main;
        }

        root.select(BOILERPLATE).remove();

        return clean(root.text());
    }

    private static String meta(org.jsoup.nodes.Document html, String name) {

        Element meta = html.selectFirst("meta[name=" + name + "]");

        return meta == null ? "" : meta.attr("content");
    }

    private static String property(org.jsoup.nodes.Document html, String property) {

        Element meta = html.selectFirst("meta[property=" + property + "]");

        return meta == null ? "" : meta.attr("content");
    }

    private static String clean(String text) {

        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
