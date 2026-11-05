package com.coolhandlabs.coolhand.services;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Settings shared by every service (coolhand-node's {@code BaseServiceConfig}).
 *
 * @param apiKey the Coolhand API key, sent as {@code X-API-Key}
 * @param silent suppress progress logging (default {@code true})
 * @param debug verbose logging; never suppresses submissions
 * @param dryRun skip every write to the Coolhand API (reads still run)
 * @param baseUrl the API origin, default {@code https://coolhandlabs.com}; must be https, except
 *     {@code http://localhost}, {@code http://127.0.0.1} and {@code http://[::1]}
 * @param requestTimeout per-call timeout, default 30 seconds (uploads use 10 minutes)
 * @param httpClient the client used to reach Coolhand; {@code null} uses a shared default that never
 *     follows redirects. A custom client must not follow redirects either, or the API key could be
 *     carried to another host.
 */
public record ServiceConfig(
        String apiKey,
        boolean silent,
        boolean debug,
        boolean dryRun,
        String baseUrl,
        Duration requestTimeout,
        HttpClient httpClient) {

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ServiceConfig}. */
    public static final class Builder {
        private String apiKey;
        private boolean silent = true;
        private boolean debug;
        private boolean dryRun;
        private String baseUrl;
        private Duration requestTimeout;
        private HttpClient httpClient;

        private Builder() {}

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder silent(boolean silent) { this.silent = silent; return this; }
        public Builder debug(boolean debug) { this.debug = debug; return this; }
        public Builder dryRun(boolean dryRun) { this.dryRun = dryRun; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder requestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }

        public ServiceConfig build() {
            return new ServiceConfig(apiKey, silent, debug, dryRun, baseUrl, requestTimeout, httpClient);
        }
    }
}
