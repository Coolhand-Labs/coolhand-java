package com.coolhandlabs.coolhand.interception;

import com.coolhandlabs.coolhand.CallData;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.MatchedPattern;
import com.coolhandlabs.coolhand.internal.DeclaredContexts;
import com.coolhandlabs.coolhand.internal.Log;
import com.coolhandlabs.coolhand.internal.UrlFilters;
import com.coolhandlabs.coolhand.internal.UrlParts;
import com.coolhandlabs.coolhand.services.PatternMatchingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides which requests to capture and turns captured traffic into log submissions: the
 * client-independent half of every integration (coolhand-node's {@code RequestMonitoringService}
 * without the global patching). Each {@code Coolhand} client owns one; integrations call
 * {@link #begin} for every request they see.
 *
 * <p>Use this to monitor an HTTP client this SDK has no integration for: call {@link #begin} on the
 * calling thread, hand it the request body, then feed the response through
 * {@link CaptureSession#response} and {@link ResponseCapture#wrap(java.io.InputStream)} (or the
 * {@code onBytes}/{@code complete}/{@code abandoned} callbacks).
 */
public final class RequestMonitor {
    /** Receives each completed call. */
    @FunctionalInterface
    public interface Submitter {
        void submit(CallData callData, MatchedPattern matchedPattern, DeclaredContext declared);
    }

    private final PatternMatchingService patterns;
    private final Submitter submitter;
    private final Log log;
    private final Duration idleTimeout;
    private final UrlFilters.SelfEndpoint selfEndpoint;
    private volatile List<String> excludeApiPatterns;
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong interceptedCalls = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger finalizing = new java.util.concurrent.atomic.AtomicInteger();

    public RequestMonitor(PatternMatchingService patterns, List<String> excludeApiPatterns, String selfApiEndpoint,
            boolean silent, Duration idleTimeout, Submitter submitter) {
        this.patterns = patterns;
        this.excludeApiPatterns = Collections.unmodifiableList(new ArrayList<>(excludeApiPatterns));
        this.selfEndpoint = UrlFilters.computeSelfEndpoint(selfApiEndpoint);
        this.log = new Log(silent);
        this.idleTimeout = idleTimeout;
        this.submitter = submitter;
    }

    /**
     * Starts capturing {@code method url} when it is a recognised LLM call that is not excluded, not
     * the Coolhand API itself and not a non-inference endpoint; empty otherwise (pass the request
     * through untouched). Call on the thread making the request: the declared context active there is
     * snapshotted now.
     *
     * @param headers request headers (any case; values redacted before logging)
     */
    public Optional<CaptureSession> begin(String method, String url, Map<String, List<String>> headers) {
        return begin(method, url, headers, DeclaredContexts.current().orElse(null));
    }

    /** {@link #begin(String, String, Map)} with an explicit declared context ({@code null} for none). */
    public Optional<CaptureSession> begin(String method, String url, Map<String, List<String>> headers,
            DeclaredContext declared) {
        try {
            totalRequests.incrementAndGet();
            log.info(() -> "🌐 REQUEST to: " + hostOf(url));
            MatchedPattern matched = patterns.matchesApiPatternFromUrl(url);
            if (matched == null) {
                return Optional.empty();
            }
            if (UrlFilters.isSelfOrExcluded(url, selfEndpoint, excludeApiPatterns)
                    || UrlFilters.isNonInferenceUrl(url, method)) {
                return Optional.empty();
            }
            long id = interceptedCalls.incrementAndGet();
            log.info("🎯 INTERCEPTING " + matched.pattern().name() + " call");
            CaptureSession session = new CaptureSession(this, id, method, url, headers, matched, declared);
            log.info("📞 Starting API call #" + id + " to " + session.sanitizedUrl());
            return Optional.of(session);
        } catch (RuntimeException e) {
            // Deciding whether to capture must never break the host's request.
            log.info("⚠️ Could not prepare capture; not intercepting this request: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Hostname-only rendering for debug logs (a URL may carry a secret in its query). */
    private static String hostOf(String url) {
        UrlParts parsed = UrlParts.parse(url);
        return parsed == null ? "unknown" : parsed.hostname;
    }

    PatternMatchingService patterns() {
        return patterns;
    }

    Log log() {
        return log;
    }

    Duration idleTimeout() {
        return idleTimeout;
    }

    void submit(CallData callData, MatchedPattern matched, DeclaredContext declared) {
        submitter.submit(callData, matched, declared);
    }

    void finalizeStarted() {
        finalizing.incrementAndGet();
    }

    void finalizeEnded() {
        finalizing.decrementAndGet();
    }

    /** Captures whose body ended but whose log has not been handed to the submitter yet. */
    public int pendingFinalizations() {
        return finalizing.get();
    }

    public List<String> getExcludeApiPatterns() {
        return excludeApiPatterns;
    }

    public void setExcludeApiPatterns(List<String> patterns) {
        UrlFilters.warnOnGlobLikePatterns(patterns);
        this.excludeApiPatterns = Collections.unmodifiableList(new ArrayList<>(patterns));
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public long interceptedCalls() {
        return interceptedCalls.get();
    }
}
