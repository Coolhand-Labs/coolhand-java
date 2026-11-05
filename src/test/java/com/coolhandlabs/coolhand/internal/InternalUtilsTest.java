package com.coolhandlabs.coolhand.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class InternalUtilsTest {
    @Test
    void cappedBufferTruncatesOnceAndIgnoresEmptyChunks() {
        AtomicInteger warnings = new AtomicInteger();
        CappedBuffer buffer = new CappedBuffer(5, warnings::incrementAndGet);
        buffer.push("abc".getBytes(StandardCharsets.UTF_8));
        buffer.push("defg".getBytes(StandardCharsets.UTF_8));
        buffer.push("h".getBytes(StandardCharsets.UTF_8));
        assertEquals("abcde", new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(buffer.isTruncated());
        assertEquals(1, warnings.get());

        CappedBuffer exact = new CappedBuffer(3);
        exact.push("abc".getBytes(StandardCharsets.UTF_8));
        exact.push(new byte[0]);
        assertFalse(exact.isTruncated());
    }

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static byte[] deflate(String s, boolean raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, raw))) {
            d.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void decompressesGzipAndBothDeflateFlavours() throws IOException {
        assertEquals("{\"a\":1}", Decompress.decompress(gzip("{\"a\":1}"), "gzip", null, false));
        assertEquals("{\"a\":1}", Decompress.decompress(gzip("{\"a\":1}"), " X-GZIP ", null, false));
        assertEquals("zlib", Decompress.decompress(deflate("zlib", false), "deflate", null, false));
        assertEquals("raw", Decompress.decompress(deflate("raw", true), "deflate", null, false));
        assertEquals("plain", Decompress.decompress("plain".getBytes(StandardCharsets.UTF_8), null, null, false));
        assertEquals("plain", Decompress.decompress("plain".getBytes(StandardCharsets.UTF_8), "identity", null, false));
    }

    @Test
    void fallsBackToTextWhenDecompressionFails() {
        AtomicInteger warned = new AtomicInteger();
        assertEquals("not gzip", Decompress.decompress("not gzip".getBytes(StandardCharsets.UTF_8), "gzip",
                m -> warned.incrementAndGet(), false));
        assertEquals(1, warned.get());
    }

    @Test
    void decodesAsMuchAsAPartialGzipPrefixAllows() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            text.append("data: {\"i\":").append(i).append("}\n\n");
        }
        byte[] full = gzip(text.toString());
        byte[] prefix = Arrays.copyOf(full, full.length - 20);
        String out = Decompress.decompress(prefix, "gzip", null, true);
        assertTrue(out.startsWith("data: {\"i\":0}"));
        assertTrue(out.length() > 1000);
    }

    @Test
    void parseBodyMirrorsJsonParse() {
        assertNull(BodyParser.parseBody(null));
        assertNull(BodyParser.parseBody(""));
        assertEquals("{\"a\":1}", BodyParser.parseBody("{\"a\":1}").toString());
        assertEquals("{\"a\":1}\n2\n\"x\"", BodyParser.parseBody("[{\"a\":1},2,\"x\"]").textValue());
        assertEquals("{\"a\":1}\n{\"b\":2}", BodyParser.parseBody("{\"a\":1}\n{\"b\":2}").textValue());
        assertEquals("not json", BodyParser.parseBody("not json").textValue());
        assertEquals(42, BodyParser.parseBody("42").intValue());
    }

    @Test
    void jsTextMatchesJavaScript() {
        assertEquals("a%20b%2Fc~!*'()", JsText.encodeURIComponent("a b/c~!*'()"));
        assertEquals("q%5Bs%5D=a+b", JsText.formEncode("q[s]") + "=" + JsText.formEncode("a b"));
        assertEquals("x", JsText.trim(" ﻿ x  \n"));
        assertEquals("2026-01-02T03:04:05.000Z", JsText.isoTimestamp(Instant.parse("2026-01-02T03:04:05Z")));
        assertEquals("2", JsText.jsNumber(2.0));
        assertEquals("0.5", JsText.jsNumber(0.5));
        assertEquals("a b", JsText.decodeFormLenient("a+b"));
        assertEquals("%zz", JsText.decodeFormLenient("%zz"));
    }

    @Test
    void urlPartsReadLikeWhatwgUrl() {
        UrlParts u = UrlParts.parse("HTTPS://User:Pw@API.Example.com:443/a/./b/../c?x=1#f");
        assertEquals("https", u.scheme);
        assertEquals("api.example.com", u.hostname);
        assertNull(u.port);
        assertEquals("/a/c", u.path);
        assertEquals("x=1", u.query);
        assertEquals("https://User:Pw@api.example.com/a/c?x=1#f", u.href());
        assertEquals("https://api.example.com/a/c?y=2#f", u.href(true, "y=2"));
        assertEquals("/", UrlParts.parse("https://example.com").path);
        assertEquals("[::1]", UrlParts.parse("http://[::1]:3000/x").hostname);
        assertEquals(3000, UrlParts.parse("http://[::1]:3000/x").port);
        assertNull(UrlParts.parse("/relative"));
        assertNull(UrlParts.parse("https://host:99999/"));
    }

    @Test
    void entityKeysMirrorTheServerRules() {
        assertNull(EntityKeys.keyProblem("Parser#call/squish:L2"));
        assertEquals("must not be blank", EntityKeys.keyProblem(""));
        assertEquals("must not contain \"//\"", EntityKeys.keyProblem("a//b"));
        assertEquals("must be at most 1000 characters", EntityKeys.descriptionProblem("x".repeat(1001)));
    }
}
