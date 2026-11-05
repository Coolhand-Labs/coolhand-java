package com.coolhandlabs.coolhand.interception;

import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.MatchedPattern;
import com.coolhandlabs.coolhand.internal.CappedBuffer;
import com.coolhandlabs.coolhand.internal.JsText;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One captured request, from {@link RequestMonitor#begin} until its response is complete. The URL,
 * request headers and declared context are fixed when the session starts (on the calling thread).
 */
public final class CaptureSession {
    final RequestMonitor monitor;
    final long id;
    final String timestamp;
    final String method;
    final String sanitizedUrl;
    final Map<String, Object> sanitizedHeaders;
    final MatchedPattern matched;
    final DeclaredContext declared;
    final String protocol;
    private final CappedBuffer requestBuffer;

    CaptureSession(RequestMonitor monitor, long id, String method, String url, Map<String, List<String>> headers,
            MatchedPattern matched, DeclaredContext declared) {
        this.monitor = monitor;
        this.id = id;
        this.timestamp = JsText.isoTimestamp(Instant.now());
        this.method = method == null ? "GET" : method;
        this.sanitizedUrl = monitor.patterns().sanitizeUrl(url);
        this.sanitizedHeaders = monitor.patterns().sanitizeHeaders(headers, matched.pattern());
        this.matched = matched;
        this.declared = declared;
        com.coolhandlabs.coolhand.internal.UrlParts parts = com.coolhandlabs.coolhand.internal.UrlParts.parse(url);
        this.protocol = parts == null ? "https" : parts.scheme;
        this.requestBuffer = new CappedBuffer(CappedBuffer.MAX_BYTES, () -> monitor.log().info(
                "⚠️ Request body for call #" + id + " exceeded " + CappedBuffer.MAX_BYTES + " bytes; truncating capture"));
    }

    /** The call number within its client. */
    public long id() {
        return id;
    }

    /** The URL as it will be logged (credential query parameters redacted). */
    public String sanitizedUrl() {
        return sanitizedUrl;
    }

    /** The declared context snapshotted when the session began, or {@code null}. */
    public DeclaredContext declared() {
        return declared;
    }

    /** Records (up to 50 MiB of) the request body. Call before the response arrives. */
    public void requestBody(byte[] bytes, int offset, int length) {
        requestBuffer.push(bytes, offset, length);
    }

    public void requestBody(byte[] bytes) {
        requestBody(bytes, 0, bytes.length);
    }

    String requestText() {
        return requestBuffer.size() == 0 ? null : new String(requestBuffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * The response arrived: returns the capture its body must be fed through. The call is logged once
     * that body ends, is abandoned, or sits unread for the idle timeout.
     */
    public ResponseCapture response(int statusCode, Map<String, List<String>> headers) {
        monitor.log().info("📥 Response received for call #" + id + ", status: " + statusCode);
        return new ResponseCapture(this, statusCode, headers);
    }

    /** The request failed before any response: nothing is logged (as in coolhand-node). */
    public void failed(Throwable error) {
        monitor.log().info("❌ Request error for call #" + id + ": " + (error == null ? "unknown" : error.getMessage()));
    }
}
