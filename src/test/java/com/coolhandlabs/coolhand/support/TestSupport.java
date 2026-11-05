package com.coolhandlabs.coolhand.support;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.CoolhandOptions;
import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class TestSupport {
    public static final String API_KEY = "ch_test_key_123";

    private TestSupport() {}

    /**
     * A patterns file recognising {@code 127.0.0.1} as {@code name}, so a local fake provider is
     * captured exactly like the real one.
     */
    public static Path localPatterns(Path dir, String name, String redactedHeader) {
        String json = "{\"patterns\":[{\"name\":\"" + name + "\",\"domains\":[\"127.0.0.1\"],"
                + "\"headers\":{\"" + redactedHeader + "\":\"[REDACTED]\"}}]}";
        try {
            Path file = dir.resolve("patterns.json");
            Files.writeString(file, json);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Coolhand coolhand(FakeServer coolhandApi, Path patternsFile) {
        return new Coolhand(CoolhandOptions.builder()
                .apiKey(API_KEY)
                .baseUrl(coolhandApi.baseUrl())
                .patternsFile(patternsFile)
                .build());
    }

    public static Coolhand coolhand(FakeServer coolhandApi, Path patternsFile, Duration idle) {
        return new Coolhand(CoolhandOptions.builder()
                .apiKey(API_KEY)
                .baseUrl(coolhandApi.baseUrl())
                .patternsFile(patternsFile)
                .captureIdleTimeout(idle)
                .build());
    }

    public static JsonNode json(String text) {
        try {
            return Json.parse(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The posted log with its timestamp checked and replaced by a placeholder. */
    public static ObjectNode normalizedLog(JsonNode posted) {
        ObjectNode copy = posted.deepCopy();
        ObjectNode raw = (ObjectNode) copy.path("llm_request_log").path("raw_request");
        String ts = raw.path("timestamp").asText();
        if (!ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")) {
            throw new AssertionError("timestamp not ISO-8601 with millis: " + ts);
        }
        raw.put("timestamp", "<timestamp>");
        JsonNode responseHeaders = raw.get("response_headers");
        if (responseHeaders instanceof ObjectNode rh && rh.has("date")) {
            rh.put("date", "<date>");
        }
        return copy;
    }
}
