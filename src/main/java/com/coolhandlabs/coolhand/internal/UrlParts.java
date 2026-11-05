package com.coolhandlabs.coolhand.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A lenient absolute-URL parser modelled on the parts of the WHATWG {@code URL} class coolhand-node
 * relies on: lowercased host, default port dropped, empty path read as {@code /}. Unlike
 * {@link java.net.URI} it accepts the characters real request URLs carry unencoded (spaces, {@code |},
 * brackets in queries), so matching and redaction never give up on a URL a client was happy to send.
 */
public final class UrlParts {
    public final String scheme;
    public final String userinfo; // null when absent
    public final String hostname; // lowercase; IPv6 literals keep their brackets, as URL#hostname does
    public final Integer port; // null when absent or the scheme's default
    public final String path; // raw, never empty
    public final String query; // raw, without '?'; null when absent
    public final String fragment; // raw, without '#'; null when absent

    private UrlParts(String scheme, String userinfo, String hostname, Integer port, String path, String query,
            String fragment) {
        this.scheme = scheme;
        this.userinfo = userinfo;
        this.hostname = hostname;
        this.port = port;
        this.path = path;
        this.query = query;
        this.fragment = fragment;
    }

    /** Parses {@code url}, or returns {@code null} when it is not an absolute {@code scheme://host} URL. */
    public static UrlParts parse(String url) {
        if (url == null) {
            return null;
        }
        String s = JsText.trim(url);
        int colon = s.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.matches("[a-z][a-z0-9+.-]*") || !s.startsWith("//", colon + 1)) {
            return null;
        }
        int authStart = colon + 3;
        int authEnd = s.length();
        for (int i = authStart; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                authEnd = i;
                break;
            }
        }
        String authority = s.substring(authStart, authEnd);
        String userinfo = null;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userinfo = authority.substring(0, at);
            authority = authority.substring(at + 1);
        }
        String host;
        String portText = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = authority.substring(0, close + 1);
            String rest = authority.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return null;
                }
                portText = rest.substring(1);
            }
        } else {
            int pc = authority.lastIndexOf(':');
            if (pc >= 0) {
                host = authority.substring(0, pc);
                portText = authority.substring(pc + 1);
            } else {
                host = authority;
            }
        }
        if (host.isEmpty() || host.chars().anyMatch(ch -> ch <= ' ')) {
            return null;
        }
        Integer port = null;
        if (portText != null && !portText.isEmpty()) {
            if (!portText.chars().allMatch(Character::isDigit) || portText.length() > 5) {
                return null;
            }
            int p = Integer.parseInt(portText);
            if (p > 65535) {
                return null;
            }
            if (p != defaultPort(scheme)) {
                port = p;
            }
        }
        String rest = s.substring(authEnd).replace('\\', '/');
        String fragment = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String path = rest.isEmpty() ? "/" : removeDotSegments(rest);
        return new UrlParts(scheme, userinfo, host.toLowerCase(Locale.ROOT), port, path, query, fragment);
    }

    public static int defaultPort(String scheme) {
        switch (scheme) {
            case "https": case "wss": return 443;
            case "http": case "ws": return 80;
            case "ftp": return 21;
            default: return -1;
        }
    }

    /** The port in use: the explicit one, else the scheme default, as text. */
    public String effectivePort() {
        return port != null ? port.toString() : "https".equals(scheme) ? "443" : "80";
    }

    public UrlParts withHostname(String newHostname) {
        return new UrlParts(scheme, userinfo, newHostname, port, path, query, fragment);
    }

    public boolean hasCredentials() {
        return userinfo != null && !userinfo.isEmpty();
    }

    /** Serializes like {@code URL#href}; {@code dropUserinfo}/{@code newQuery} let callers rewrite parts. */
    public String href(boolean dropUserinfo, String newQuery) {
        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://");
        if (!dropUserinfo && hasCredentials()) {
            out.append(userinfo).append('@');
        }
        out.append(hostname);
        if (port != null) {
            out.append(':').append(port);
        }
        out.append(path);
        String q = newQuery != null ? newQuery : query;
        if (q != null && !q.isEmpty()) {
            out.append('?').append(q);
        }
        if (fragment != null) {
            out.append('#').append(fragment);
        }
        return out.toString();
    }

    public String href() {
        return href(false, null);
    }

    /** RFC 3986 dot-segment removal, also treating {@code %2e} as a dot as WHATWG parsing does. */
    public static String removeDotSegments(String path) {
        String[] parts = path.split("/", -1);
        List<String> out = new ArrayList<>();
        boolean trailingSlash = false;
        for (int i = 1; i < parts.length; i++) { // parts[0] is the empty string before the leading '/'
            String seg = parts[i];
            String lower = seg.toLowerCase(Locale.ROOT);
            boolean last = i == parts.length - 1;
            if (lower.equals(".") || lower.equals("%2e")) {
                trailingSlash = last;
                continue;
            }
            if (lower.equals("..") || lower.equals(".%2e") || lower.equals("%2e.") || lower.equals("%2e%2e")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                trailingSlash = last;
                continue;
            }
            out.add(seg);
            trailingSlash = false;
        }
        String joined = "/" + String.join("/", out);
        if (trailingSlash && !joined.endsWith("/")) {
            joined += "/";
        }
        return joined;
    }
}
