package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.coolhandlabs.coolhand.internal.JsText;

/**
 * One captured LLM request and its response: the {@code raw_request} of a log submission
 * (coolhand-node's {@code CoolhandCallData}). Bodies are whatever Jackson can serialize — a parsed
 * {@code JsonNode}, a {@code Map}, a {@code String} — and headers are already redacted when the SDK
 * builds the record itself.
 *
 * @param id per-client call number
 * @param timestamp when the call started, ISO-8601 UTC with milliseconds
 * @param method HTTP method
 * @param url the request URL, with credential query parameters and userinfo redacted
 * @param headers request headers (lowercase names, credentials redacted)
 * @param requestBody parsed request body, or {@code null}
 * @param responseBody parsed response body, or {@code null}
 * @param responseHeaders response headers, or {@code null}
 * @param statusCode HTTP status, or {@code null}
 * @param protocol {@code "https"} or {@code "http"} for intercepted calls
 * @param streamIncomplete {@code true} when the response was abandoned before it ended and
 *     {@code responseBody} holds what was read so far; {@code null} (omitted) otherwise
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"id", "timestamp", "method", "url", "headers", "request_body", "response_body",
        "response_headers", "status_code", "protocol", "stream_incomplete"})
public record CallData(
        @JsonProperty("id") long id,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("method") String method,
        @JsonProperty("url") String url,
        @JsonProperty("headers") Map<String, Object> headers,
        @JsonProperty("request_body") Object requestBody,
        @JsonProperty("response_body") Object responseBody,
        @JsonProperty("response_headers") Map<String, Object> responseHeaders,
        @JsonProperty("status_code") Integer statusCode,
        @JsonProperty("protocol") String protocol,
        @JsonProperty("stream_incomplete") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean streamIncomplete) {

    public CallData {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        responseHeaders = responseHeaders == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(responseHeaders));
        // Only a strict true is ever sent.
        streamIncomplete = Boolean.TRUE.equals(streamIncomplete) ? Boolean.TRUE : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for manual submissions via {@link Coolhand#logRequest(CallData)}. */
    public static final class Builder {
        private long id = 1;
        private String timestamp = JsText.isoTimestamp(Instant.now());
        private String method = "POST";
        private String url;
        private Map<String, Object> headers = new LinkedHashMap<>();
        private Object requestBody;
        private Object responseBody;
        private Map<String, Object> responseHeaders;
        private Integer statusCode;
        private String protocol = "https";
        private Boolean streamIncomplete;

        private Builder() {}

        public Builder id(long id) { this.id = id; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = JsText.isoTimestamp(timestamp); return this; }
        public Builder timestamp(String timestamp) { this.timestamp = timestamp; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder url(String url) { this.url = url; return this; }
        public Builder headers(Map<String, ?> headers) { this.headers = new LinkedHashMap<>(headers); return this; }
        public Builder header(String name, Object value) { this.headers.put(name, value); return this; }
        public Builder requestBody(Object body) { this.requestBody = body; return this; }
        public Builder responseBody(Object body) { this.responseBody = body; return this; }
        public Builder responseHeaders(Map<String, ?> headers) {
            this.responseHeaders = headers == null ? null : new LinkedHashMap<>(headers);
            return this;
        }
        public Builder statusCode(Integer statusCode) { this.statusCode = statusCode; return this; }
        public Builder protocol(String protocol) { this.protocol = protocol; return this; }
        public Builder streamIncomplete(boolean incomplete) { this.streamIncomplete = incomplete; return this; }

        public CallData build() {
            return new CallData(id, timestamp, method, url, headers, requestBody, responseBody, responseHeaders,
                    statusCode, protocol, streamIncomplete);
        }
    }
}
