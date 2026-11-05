package com.coolhandlabs.coolhand.interception;

import com.coolhandlabs.coolhand.CallData;
import com.coolhandlabs.coolhand.internal.Background;
import com.coolhandlabs.coolhand.internal.BodyParser;
import com.coolhandlabs.coolhand.internal.CappedBuffer;
import com.coolhandlabs.coolhand.internal.Decompress;
import com.coolhandlabs.coolhand.services.PatternMatchingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures a response body as the host reads it, bounded at 50 MiB, and logs the call exactly once:
 * when the body ends ({@link #complete()}), when the host abandons it ({@link #abandoned()}, sent with
 * {@code stream_incomplete: true} and the partial body), or when the host stops reading for the idle
 * timeout (30 seconds by default), whichever comes first. The host's data is never altered.
 */
public final class ResponseCapture {
    private static final List<String> STREAMING_TYPES =
            List.of("text/event-stream", "application/x-ndjson", "application/jsonl", "application/vnd.amazon.eventstream");

    private final CaptureSession session;
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final boolean streaming;
    private final CappedBuffer buffer;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile ScheduledFuture<?> idleTimer;

    ResponseCapture(CaptureSession session, int statusCode, Map<String, List<String>> headers) {
        this.session = session;
        this.statusCode = statusCode;
        this.headers = headers == null ? Map.of() : headers;
        String contentType = header("content-type");
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        this.streaming = STREAMING_TYPES.stream().anyMatch(lower::contains);
        this.buffer = new CappedBuffer(CappedBuffer.MAX_BYTES, () -> session.monitor.log().info("⚠️ Response body for call #"
                + session.id + " exceeded " + CappedBuffer.MAX_BYTES + " bytes; truncating capture"));
        touch();
    }

    /** First value of a response header (case-insensitive), or {@code null}. */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /**
     * True for streamed responses (server-sent events, NDJSON, AWS event streams). Closing such a body
     * early logs what was read as incomplete; closing any other body early first reads the rest of it
     * (up to the cap) so a parser that stops at the end of the JSON value still logs a complete call.
     */
    public boolean isStreaming() {
        return streaming;
    }

    public boolean isFinished() {
        return finished.get();
    }

    /** Body bytes the host received. */
    public void onBytes(byte[] bytes, int offset, int length) {
        if (finished.get() || length <= 0) {
            return;
        }
        buffer.push(bytes, offset, length);
        touch();
    }

    /** The body was read to its end. */
    public void complete() {
        finish(false);
    }

    /** The host stopped before the end (cancel, close, error): logged with {@code stream_incomplete}. */
    public void abandoned() {
        finish(true);
    }

    /** A host-facing stream that captures what passes through it. */
    public InputStream wrap(InputStream body) {
        return new CapturingInputStream(body, this);
    }

    /** Restarts the idle timer; integrations call this whenever the host shows activity. */
    public void touch() {
        Duration timeout = session.monitor.idleTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative() || finished.get()) {
            return;
        }
        ScheduledFuture<?> previous = idleTimer;
        if (previous != null) {
            previous.cancel(false);
        }
        idleTimer = Background.timers().schedule(this::abandoned, timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (finished.get()) {
            idleTimer.cancel(false);
        }
    }

    private void finish(boolean incomplete) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> timer = idleTimer;
        if (timer != null) {
            timer.cancel(false);
        }
        session.monitor.finalizeStarted();
        try {
            Background.executor().execute(() -> submit(incomplete));
        } catch (RuntimeException e) {
            submit(incomplete);
        }
    }

    private void submit(boolean incomplete) {
        RequestMonitor monitor = session.monitor;
        try {
            PatternMatchingService patterns = monitor.patterns();
            JsonNode requestBody = BodyParser.parseAndSanitize(session.requestText(), patterns::sanitizeBody,
                    e -> monitor.log().info("⚠️ Request body sanitize failed for call #" + session.id + ": " + e.getMessage()));
            String text = Decompress.decompress(buffer.toByteArray(), header("content-encoding"),
                    msg -> monitor.log().info(msg), incomplete);
            JsonNode responseBody = BodyParser.parseAndSanitize(text, patterns::sanitizeBody,
                    e -> monitor.log().info("⚠️ Response body sanitize failed for call #" + session.id + ": " + e.getMessage()));
            CallData callData = new CallData(session.id, session.timestamp, session.method, session.sanitizedUrl,
                    session.sanitizedHeaders, requestBody, responseBody,
                    patterns.sanitizeHeaders(headers, session.matched.pattern()), statusCode, session.protocol,
                    incomplete ? Boolean.TRUE : null);
            monitor.submit(callData, session.matched, session.declared);
        } catch (RuntimeException e) {
            monitor.log().info("⚠️ Could not complete call #" + session.id + ": " + e.getMessage());
        } finally {
            monitor.finalizeEnded();
        }
    }
}
