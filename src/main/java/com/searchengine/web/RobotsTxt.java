package com.searchengine.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules of a site's {@code robots.txt} that apply to one crawler.
 * <p>
 * Follows the Robots Exclusion Protocol (RFC 9309): the group naming our
 * user agent wins over {@code *}; within it the longest matching
 * {@code Allow}/{@code Disallow} rule decides (Allow wins a tie); {@code *}
 * matches any characters and a trailing {@code $} anchors the end. A
 * {@code Crawl-delay} is honoured (capped by the caller).
 */
public final class RobotsTxt {

    private record Rule(String pattern, boolean allow) {
    }

    private final List<Rule> rules;

    private final double crawlDelaySeconds;

    private RobotsTxt(List<Rule> rules, double crawlDelaySeconds) {

        this.rules = rules;

        this.crawlDelaySeconds = crawlDelaySeconds;
    }

    /**
     * No robots.txt (or an unreadable one): everything is allowed.
     */
    public static RobotsTxt allowAll() {

        return new RobotsTxt(List.of(), 0);
    }

    /**
     * robots.txt returned a server error: RFC 9309 says to assume nothing is allowed.
     */
    public static RobotsTxt disallowAll() {

        return new RobotsTxt(List.of(new Rule("/", false)), 0);
    }

    /**
     * @param agentToken our crawler's product token, e.g. "siftbot"
     */
    public static RobotsTxt parse(String content, String agentToken) {

        String agent = agentToken.toLowerCase(Locale.ROOT);

        List<Rule> specific = new ArrayList<>();

        List<Rule> wildcard = new ArrayList<>();

        double specificDelay = -1;

        double wildcardDelay = -1;

        boolean inSpecific = false;

        boolean inWildcard = false;

        boolean sawSpecific = false;

        boolean lastWasAgent = false;

        for (String raw : content.split("\r?\n|\r")) {

            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;

            int colon = line.indexOf(':');

            if (colon < 0) {

                continue;
            }

            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);

            String value = line.substring(colon + 1).strip();

            if (key.equals("user-agent")) {

                // Consecutive User-agent lines share one group.
                if (!lastWasAgent) {

                    inSpecific = false;

                    inWildcard = false;
                }

                String name = value.toLowerCase(Locale.ROOT);

                if (name.equals("*")) {

                    inWildcard = true;

                } else if (!name.isEmpty() && agent.contains(name.split("/")[0])) {

                    inSpecific = true;

                    sawSpecific = true;
                }

                lastWasAgent = true;

                continue;
            }

            lastWasAgent = false;

            if (key.equals("allow") || key.equals("disallow")) {

                // An empty Disallow allows everything: no rule.
                if (value.isEmpty()) {

                    continue;
                }

                Rule rule = new Rule(value, key.equals("allow"));

                if (inSpecific) {

                    specific.add(rule);
                }

                if (inWildcard) {

                    wildcard.add(rule);
                }

            } else if (key.equals("crawl-delay")) {

                try {

                    double delay = Double.parseDouble(value);

                    if (inSpecific) {

                        specificDelay = delay;
                    }

                    if (inWildcard) {

                        wildcardDelay = delay;
                    }

                } catch (NumberFormatException ignored) {
                    // Malformed delay: ignore the line.
                }
            }
        }

        return sawSpecific
                ? new RobotsTxt(specific, Math.max(0, specificDelay))
                : new RobotsTxt(wildcard, Math.max(0, wildcardDelay));
    }

    /**
     * @param pathAndQuery the URL's path plus query, e.g. "/search?q=x"
     */
    public boolean isAllowed(String pathAndQuery) {

        String path = pathAndQuery.isEmpty() ? "/" : pathAndQuery;

        if (path.equals("/robots.txt")) {

            return true;
        }

        Rule best = null;

        int bestLength = -1;

        for (Rule rule : rules) {

            if (matches(rule.pattern(), path)) {

                int length = rule.pattern().length();

                if (length > bestLength || (length == bestLength && rule.allow())) {

                    best = rule;

                    bestLength = length;
                }
            }
        }

        return best == null || best.allow();
    }

    public double crawlDelaySeconds() {

        return crawlDelaySeconds;
    }

    /**
     * Pattern match with '*' (any run of characters) and a trailing '$'.
     */
    static boolean matches(String pattern, String path) {

        boolean anchored = pattern.endsWith("$");

        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;

        return matchFrom(p, 0, path, 0, anchored);
    }

    private static boolean matchFrom(String p, int pi, String s, int si, boolean anchored) {

        while (pi < p.length()) {

            char c = p.charAt(pi);

            if (c == '*') {

                // Collapse repeated stars, then try every split point.
                while (pi < p.length() && p.charAt(pi) == '*') {

                    pi++;
                }

                if (pi == p.length()) {

                    return true;
                }

                for (int k = si; k <= s.length(); k++) {

                    if (matchFrom(p, pi, s, k, anchored)) {

                        return true;
                    }
                }

                return false;
            }

            if (si >= s.length() || s.charAt(si) != c) {

                return false;
            }

            pi++;

            si++;
        }

        return !anchored || si == s.length();
    }
}
