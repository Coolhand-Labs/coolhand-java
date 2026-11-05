package com.coolhandlabs.coolhand.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * JavaScript string semantics the wire format depends on, so this SDK produces what coolhand-node
 * produces: {@code String.prototype.trim}, {@code encodeURIComponent}, {@code URLSearchParams}
 * encoding and {@code Date.prototype.toISOString}.
 */
public final class JsText {
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JsText() {}

    /** Whitespace and line terminators as {@code String.prototype.trim} defines them. */
    public static boolean isJsWhitespace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000B': case '\f': case '\r': case ' ':
            case ' ': case ' ': case ' ': case ' ': case ' ':
            case ' ': case '　': case '﻿':
                return true;
            default:
                return c >= ' ' && c <= ' ';
        }
    }

    public static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    public static boolean isBlank(String s) {
        return s == null || trim(s).isEmpty();
    }

    /** {@code encodeURIComponent}: everything but {@code A-Z a-z 0-9 - _ . ! ~ * ' ( )} is percent-encoded. */
    public static String encodeURIComponent(String s) {
        return percentEncode(s, "-_.!~*'()", false);
    }

    /** {@code application/x-www-form-urlencoded} as {@code URLSearchParams} writes it (space becomes {@code +}). */
    public static String formEncode(String s) {
        return percentEncode(s, "*-._", true);
    }

    private static String percentEncode(String s, String safe, boolean spaceAsPlus) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || safe.indexOf(b) >= 0) {
                out.append((char) b);
            } else if (b == ' ' && spaceAsPlus) {
                out.append('+');
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** Decodes {@code %XX} escapes (and {@code +} when {@code plusAsSpace}); throws on a malformed escape. */
    public static String decode(String s, boolean plusAsSpace) {
        if (s.indexOf('%') < 0 && !(plusAsSpace && s.indexOf('+') >= 0)) {
            return s;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("URI malformed");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("URI malformed");
                }
                bytes.write((hi << 4) | lo);
                i += 2;
                continue;
            }
            if (bytes.size() > 0) {
                out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                bytes.reset();
            }
            out.append(plusAsSpace && c == '+' ? ' ' : c);
        }
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /**
     * Percent-decoding as {@code URLSearchParams} parses a query: {@code +} is a space and an invalid
     * escape is kept literally rather than rejected.
     */
    public static String decodeFormLenient(String s) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                bytes.write((Character.digit(s.charAt(i + 1), 16) << 4) | Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else if (c == '+') {
                bytes.write(' ');
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                    encoded = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** {@code new Date().toISOString()}: UTC with millisecond precision, e.g. {@code 2026-01-01T00:00:00.000Z}. */
    public static String isoTimestamp(Instant instant) {
        return ISO_MILLIS.format(instant);
    }

    /** {@code String(number)} as JavaScript prints it: {@code 2.0} becomes {@code "2"}. */
    public static String jsNumber(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e21) {
                return new java.math.BigDecimal(d).toPlainString();
            }
            return Double.toString(d);
        }
        if (n instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return n.toString();
    }

    /** Number of Unicode code points (what {@code Array.from(s).length} counts). */
    public static int codePointLength(String s) {
        return s.codePointCount(0, s.length());
    }
}
