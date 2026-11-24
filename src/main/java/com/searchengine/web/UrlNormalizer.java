package com.searchengine.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Reduces equivalent URLs to one canonical spelling so that duplicates are
 * detected and links resolve to the right page: lower-case scheme and host,
 * no default port, no fragment, no common tracking parameters, and "/" for an
 * empty path. Only http(s) URLs are accepted.
 */
public final class UrlNormalizer {

    static final int MAX_LENGTH = 2048;

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "yclid", "igshid", "_ga", "ref_src");

    private UrlNormalizer() {
    }

    /**
     * @return the normalized URL, or {@code null} if it is not a usable http(s) URL
     */
    public static String normalize(String url) {

        if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {

            return null;
        }

        URI uri;

        try {

            uri = new URI(url.strip().replace(" ", "%20"));

        } catch (URISyntaxException e) {

            return null;
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!"http".equals(scheme) && !"https".equals(scheme)) {

            return null;
        }

        String host = uri.getHost();

        if (host == null || host.isEmpty()) {

            return null;
        }

        host = host.toLowerCase(Locale.ROOT);

        if (host.endsWith(".")) {

            host = host.substring(0, host.length() - 1);
        }

        int port = uri.getPort();

        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);

        String path = uri.getRawPath();

        if (path == null || path.isEmpty()) {

            path = "/";
        }

        StringBuilder sb = new StringBuilder(url.length());

        sb.append(scheme).append("://").append(host);

        if (!defaultPort) {

            sb.append(':').append(port);
        }

        sb.append(path);

        String query = cleanQuery(uri.getRawQuery());

        if (!query.isEmpty()) {

            sb.append('?').append(query);
        }

        return sb.length() > MAX_LENGTH ? null : sb.toString();
    }

    private static String cleanQuery(String rawQuery) {

        if (rawQuery == null || rawQuery.isEmpty()) {

            return "";
        }

        StringBuilder kept = new StringBuilder();

        for (String pair : rawQuery.split("&")) {

            if (pair.isEmpty()) {

                continue;
            }

            String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;

            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.startsWith("utm_") || TRACKING_PARAMS.contains(lower)) {

                continue;
            }

            if (!kept.isEmpty()) {

                kept.append('&');
            }

            kept.append(pair);
        }

        return kept.toString();
    }

    /**
     * Host of a normalized URL, without a leading "www.", or "" if none.
     */
    public static String host(String url) {

        try {

            String host = URI.create(url).getHost();

            if (host == null) {

                return "";
            }

            host = host.toLowerCase(Locale.ROOT);

            return host.startsWith("www.") ? host.substring(4) : host;

        } catch (IllegalArgumentException e) {

            return "";
        }
    }
}
