package com.coolhandlabs.coolhand.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Decodes a captured response body according to its {@code Content-Encoding} for logging: gzip,
 * x-gzip, deflate (zlib-wrapped, falling back to raw deflate) and, when a Brotli decoder
 * ({@code org.brotli:dec}, as used by {@code okhttp-brotli}) is on the classpath, br. Unknown or
 * absent encodings, and br without a decoder, are returned as UTF-8 text unchanged.
 */
public final class Decompress {
    private static final Constructor<?> BROTLI = brotliConstructor();

    private Decompress() {}

    private static Constructor<?> brotliConstructor() {
        try {
            return Class.forName("org.brotli.dec.BrotliInputStream").getConstructor(InputStream.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    public static boolean brotliAvailable() {
        return BROTLI != null;
    }

    /**
     * @param partial the bytes are a prefix of the body (an abandoned stream): decode as much as the
     *     bytes allow instead of failing on the missing end and logging the compressed bytes as text.
     */
    public static String decompress(byte[] buffer, String encoding, Consumer<String> onWarn, boolean partial) {
        if (encoding == null || encoding.isEmpty()) {
            return utf8(buffer);
        }
        String enc = JsText.trim(encoding).toLowerCase(Locale.ROOT);
        switch (enc) {
            case "gzip":
            case "x-gzip":
                return decode(buffer, enc, partial, onWarn, in -> new GZIPInputStream(in));
            case "deflate": {
                String zlib = tryDecode(buffer, partial, in -> new InflaterInputStream(in, new Inflater(false)));
                if (zlib != null) {
                    return zlib;
                }
                return decode(buffer, enc, partial, onWarn, in -> new InflaterInputStream(in, new Inflater(true)));
            }
            case "br":
                if (BROTLI == null) {
                    return utf8(buffer);
                }
                return decode(buffer, enc, partial, onWarn, in -> {
                    try {
                        return (InputStream) BROTLI.newInstance(in);
                    } catch (ReflectiveOperationException e) {
                        throw new IOException(e);
                    }
                });
            default:
                return utf8(buffer);
        }
    }

    private interface Opener {
        InputStream open(InputStream in) throws IOException;
    }

    private static String decode(byte[] buffer, String enc, boolean partial, Consumer<String> onWarn, Opener opener) {
        String out = tryDecode(buffer, partial, opener);
        if (out != null) {
            return out;
        }
        if (onWarn != null) {
            onWarn.accept("⚠️ Decompression failed for encoding '" + enc + "'");
        }
        return utf8(buffer);
    }

    /** Decoded text, or null on failure (with {@code partial}, whatever decoded before the failure counts). */
    private static String tryDecode(byte[] buffer, boolean partial, Opener opener) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        try (InputStream in = opener.open(new ByteArrayInputStream(buffer))) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                int room = CappedBuffer.MAX_BYTES - out.size();
                if (n >= room) {
                    out.write(chunk, 0, room);
                    break;
                }
                out.write(chunk, 0, n);
            }
            return utf8(out.toByteArray());
        } catch (IOException | RuntimeException e) {
            if (partial && out.size() > 0) {
                return utf8(out.toByteArray());
            }
            return null;
        }
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
