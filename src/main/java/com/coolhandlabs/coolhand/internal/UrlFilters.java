package com.coolhandlabs.coolhand.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL-level filters shared by every interception path: {@code excludeApiPatterns} substring matching,
 * the self-endpoint guard and the non-inference filter (ports of coolhand-node's
 * {@code exclude-patterns.ts}, {@code self-endpoint.ts} and {@code non-inference-filter.ts}).
 */
public final class UrlFilters {
    static final String EXCLUDE_PATTERNS_DOCS_URL =
            "https://github.com/Coolhand-Labs/coolhand-java/blob/main/docs/exclude-patterns.md";

    private static final List<Pattern> IGNORED_GET_PATH_PATTERNS = List.of(
            Pattern.compile("^/api/directory/servers(\\?.*)?$"), // MCP server directory listing
            Pattern.compile("^/v1/environments/[^/]+/work/poll(\\?.*)?$")); // managed-agents polling
    private static final List<Pattern> IGNORED_ANY_METHOD_PATH_PATTERNS = List.of(
            Pattern.compile("^/api/event_logging/")); // Claude Code telemetry batches

    private UrlFilters() {}

    /** Drops a single trailing dot and lowercases, so {@code API.example.com.} equals {@code api.example.com}. */
    public static String normalizeHostname(String hostname) {
        String lower = hostname.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    // ------------------------------------------------------------------ exclude patterns

    private static List<String> canonicalCandidates(String url) {
        UrlParts parsed = UrlParts.parse(url);
        if (parsed == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(2);
        out.add(parsed.href());
        if (parsed.hostname.endsWith(".")) {
            out.add(parsed.withHostname(normalizeHostname(parsed.hostname)).href());
        }
        return out;
    }

    /**
     * Substring (not glob) exclusion: an entry matches the URL as given, or its canonical form (host
     * lowercased, one trailing host dot dropped, empty path as {@code /}). Empty entries are ignored,
     * since they would match every URL.
     */
    public static boolean matchesExcludePattern(String url, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        List<String> valid = new ArrayList<>();
        for (String p : patterns) {
            if (p != null && !p.isEmpty()) {
                valid.add(p);
            }
        }
        for (String p : valid) {
            if (url.contains(p)) {
                return true;
            }
        }
        if (valid.isEmpty()) {
            return false;
        }
        List<String> candidates = canonicalCandidates(url);
        for (String p : valid) {
            for (String c : candidates) {
                if (c.contains(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Warns (whatever {@code silent} says) about entries containing {@code *}, which only match literally. */
    public static void warnOnGlobLikePatterns(List<String> patterns) {
        if (patterns == null) {
            return;
        }
        for (String p : patterns) {
            if (p == null) {
                Log.warn("[coolhand-java] excludeApiPatterns entry null is ignored: entries must be strings, matched as "
                        + "literal substrings of the full URL (not regular expressions or globs). See " + EXCLUDE_PATTERNS_DOCS_URL);
            } else if (p.contains("*")) {
                Log.warn("[coolhand-java] excludeApiPatterns entry \"" + p + "\" contains \"*\", but entries are matched as "
                        + "literal substrings of the full URL, not globs — it will only match URLs that contain \"*\" "
                        + "verbatim. See " + EXCLUDE_PATTERNS_DOCS_URL);
            }
        }
    }

    // ------------------------------------------------------------------ self endpoint

    /** The Coolhand API's own host and effective port, never intercepted. */
    public record SelfEndpoint(String hostname, String port) {}

    public static SelfEndpoint computeSelfEndpoint(String apiEndpoint) {
        UrlParts url = UrlParts.parse(apiEndpoint);
        return url == null ? null : new SelfEndpoint(normalizeHostname(url.hostname), url.effectivePort());
    }

    public static boolean isSelfEndpointUrl(String url, SelfEndpoint self) {
        if (self == null) {
            return false;
        }
        UrlParts parsed = UrlParts.parse(url);
        return parsed != null && normalizeHostname(parsed.hostname).equals(self.hostname())
                && parsed.effectivePort().equals(self.port());
    }

    public static boolean isSelfOrExcluded(String url, SelfEndpoint self, List<String> excludePatterns) {
        return isSelfEndpointUrl(url, self) || matchesExcludePattern(url, excludePatterns);
    }

    // ------------------------------------------------------------------ non-inference

    /** Anthropic endpoints that are not inference (telemetry, MCP directory, agent polling). */
    public static boolean isNonInferenceUrl(String url, String method) {
        UrlParts u = UrlParts.parse(url);
        if (u == null || !"api.anthropic.com".equals(u.hostname)) {
            return false;
        }
        for (Pattern p : IGNORED_ANY_METHOD_PATH_PATTERNS) {
            if (p.matcher(u.path).find()) {
                return true;
            }
        }
        if (!"GET".equals(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        String pathAndSearch = u.path + (u.query != null && !u.query.isEmpty() ? "?" + u.query : "");
        for (Pattern p : IGNORED_GET_PATH_PATTERNS) {
            if (p.matcher(pathAndSearch).find()) {
                return true;
            }
        }
        return false;
    }
}
