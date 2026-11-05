package com.coolhandlabs.coolhand;

import com.coolhandlabs.coolhand.internal.JsText;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a {@link Coolhand} client (coolhand-node's {@code CoolhandOptions}).
 *
 * @param apiKey Coolhand API key (required to construct a client)
 * @param silent suppress progress logging; default {@code true}
 * @param patternsFile a custom provider-patterns JSON file replacing the bundled {@code api-patterns.json}
 * @param debug verbose logging (never suppresses submissions; use {@code dryRun} for that)
 * @param dryRun skip every write to the Coolhand API; the payload is logged instead when not silent
 * @param baseUrl the API origin; default {@code https://coolhandlabs.com}
 * @param requestTimeout timeout for Coolhand API calls; default 30 seconds
 * @param excludeApiPatterns URL substrings (not globs) never captured; {@code null} means
 *     {@link Coolhand#DEFAULT_EXCLUDE_API_PATTERNS}, an empty list excludes nothing
 * @param captureIdleTimeout how long a response body may go unread before the call is logged with
 *     what was read so far; default 30 seconds ({@code Duration.ZERO} disables it)
 * @param httpClient the {@link HttpClient} used to reach Coolhand; {@code null} for the shared default
 */
public record CoolhandOptions(
        String apiKey,
        boolean silent,
        Path patternsFile,
        boolean debug,
        boolean dryRun,
        String baseUrl,
        Duration requestTimeout,
        List<String> excludeApiPatterns,
        Duration captureIdleTimeout,
        HttpClient httpClient) {

    /** Default for {@link #captureIdleTimeout()}. */
    public static final Duration DEFAULT_CAPTURE_IDLE_TIMEOUT = Duration.ofSeconds(30);

    public CoolhandOptions {
        excludeApiPatterns = excludeApiPatterns == null ? null
                : Collections.unmodifiableList(new ArrayList<>(excludeApiPatterns));
        captureIdleTimeout = captureIdleTimeout == null ? DEFAULT_CAPTURE_IDLE_TIMEOUT : captureIdleTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Options read from the process environment; see {@link #fromEnv(Map)}. */
    public static CoolhandOptions fromEnv() {
        return fromEnv(System.getenv());
    }

    /**
     * Options read from {@code env}, using the variables coolhand-node's auto-monitor reads:
     * {@code COOLHAND_API_KEY}, {@code COOLHAND_SILENT} (anything but {@code false} is silent),
     * {@code COOLHAND_PATTERNS_FILE}, {@code COOLHAND_DEBUG}, {@code COOLHAND_DRY_RUN} ({@code true}
     * enables), {@code COOLHAND_BASE_URL} and {@code COOLHAND_EXCLUDE_API_PATTERNS} (comma-separated;
     * a value with no non-blank entry keeps the defaults).
     */
    public static CoolhandOptions fromEnv(Map<String, String> env) {
        Builder b = builder();
        String key = env.get("COOLHAND_API_KEY");
        if (key != null && !JsText.isBlank(key)) {
            b.apiKey(key);
        }
        b.silent(!"false".equals(env.get("COOLHAND_SILENT")));
        String patterns = env.get("COOLHAND_PATTERNS_FILE");
        if (patterns != null && !patterns.isEmpty()) {
            b.patternsFile(Path.of(patterns));
        }
        b.debug("true".equals(env.get("COOLHAND_DEBUG")));
        b.dryRun("true".equals(env.get("COOLHAND_DRY_RUN")));
        String baseUrl = env.get("COOLHAND_BASE_URL");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            b.baseUrl(baseUrl);
        }
        String exclude = env.get("COOLHAND_EXCLUDE_API_PATTERNS");
        if (exclude != null && !exclude.isEmpty()) {
            List<String> parsed = new ArrayList<>();
            for (String part : exclude.split(",")) {
                String trimmed = JsText.trim(part);
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
            if (!parsed.isEmpty()) {
                b.excludeApiPatterns(parsed);
            }
        }
        return b.build();
    }

    /** True when an API key is present (not blank). */
    public boolean hasApiKey() {
        return apiKey != null && !JsText.isBlank(apiKey);
    }

    public Builder toBuilder() {
        return new Builder().apiKey(apiKey).silent(silent).patternsFile(patternsFile).debug(debug).dryRun(dryRun)
                .baseUrl(baseUrl).requestTimeout(requestTimeout).excludeApiPatterns(excludeApiPatterns)
                .captureIdleTimeout(captureIdleTimeout).httpClient(httpClient);
    }

    /** Builder for {@link CoolhandOptions}. */
    public static final class Builder {
        private String apiKey;
        private boolean silent = true;
        private Path patternsFile;
        private boolean debug;
        private boolean dryRun;
        private String baseUrl;
        private Duration requestTimeout;
        private List<String> excludeApiPatterns;
        private Duration captureIdleTimeout;
        private HttpClient httpClient;

        private Builder() {}

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder silent(boolean silent) { this.silent = silent; return this; }
        public Builder patternsFile(Path patternsFile) { this.patternsFile = patternsFile; return this; }
        public Builder debug(boolean debug) { this.debug = debug; return this; }
        public Builder dryRun(boolean dryRun) { this.dryRun = dryRun; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder requestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; return this; }
        public Builder excludeApiPatterns(List<String> patterns) { this.excludeApiPatterns = patterns; return this; }
        public Builder captureIdleTimeout(Duration timeout) { this.captureIdleTimeout = timeout; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }

        public CoolhandOptions build() {
            return new CoolhandOptions(apiKey, silent, patternsFile, debug, dryRun, baseUrl, requestTimeout,
                    excludeApiPatterns, captureIdleTimeout, httpClient);
        }
    }
}
