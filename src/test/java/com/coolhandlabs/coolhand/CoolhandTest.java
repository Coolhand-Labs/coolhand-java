package com.coolhandlabs.coolhand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoolhandTest {
    private final FakeServer api = new FakeServer();

    @AfterEach
    void close() {
        api.close();
        Coolhand.resetGlobalMonitoring();
    }

    private Coolhand coolhand() {
        return new Coolhand(CoolhandOptions.builder().apiKey(TestSupport.API_KEY).baseUrl(api.baseUrl()).build());
    }

    @Test
    void requiresAnApiKey() {
        assertEquals("API key is required", assertThrows(IllegalArgumentException.class,
                () -> new Coolhand(CoolhandOptions.builder().build())).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Coolhand(CoolhandOptions.builder().apiKey(" ").build()));
    }

    @Test
    void rejectsAnInvalidBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new Coolhand(CoolhandOptions.builder().apiKey("k").baseUrl("http://example.com").build()));
    }

    @Test
    void statsStartAtZero() {
        assertEquals(new CoolhandStats(0, 0, api.baseUrl() + "/api/v2/llm_request_logs"), coolhand().getStats());
    }

    @Test
    void excludePatternsDefaultAndCanBeReplaced() {
        Coolhand c = coolhand();
        assertEquals(Coolhand.DEFAULT_EXCLUDE_API_PATTERNS, c.getExcludeApiPatterns());
        c.setExcludeApiPatterns(List.of("/internal/"));
        assertEquals(List.of("/internal/"), c.getExcludeApiPatterns());
        Coolhand none = new Coolhand(CoolhandOptions.builder().apiKey("k").excludeApiPatterns(List.of()).build());
        assertEquals(List.of(), none.getExcludeApiPatterns());
    }

    @Test
    void logRequestSendsTheDeclaredContextActiveOnTheCallingThread() {
        Coolhand c = coolhand();
        CallData call = CallData.builder().url("https://api.anthropic.com/v1/messages").statusCode(200).build();
        Coolhand.withContext(DeclaredContext.builder().reporter("me").workload("w", "W", null).build(),
                () -> c.logRequest(call, null, Map.of("project_path", "/p")));
        JsonNode log = api.take().json().get("llm_request_log");
        assertEquals("coolhand-java-" + Version.PACKAGE_VERSION + "-manual", log.get("collector").asText());
        assertEquals(TestSupport.json("{\"reporter\":\"me\",\"workload\":{\"key\":\"w\",\"name\":\"W\"}}"), log.get("declared"));
        assertEquals("/p", log.at("/metadata/project_path").asText());
        assertTrue(log.at("/raw_request/request_body").isNull());
    }

    @Test
    void createFeedbackReturnsTheParsedResponseOrEmpty() {
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(201, "{\"id\":\"f\",\"llm_request_log_id\":\"L1\"}"));
        Coolhand c = coolhand();
        assertEquals("L1", c.createFeedback(LlmRequestLogFeedback.builder().llmProviderUniqueId("msg")
                .sentiment(Sentiment.LIKE).creatorType(CreatorType.AGENT).build()).orElseThrow().llmRequestLogId());
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(500, "{}"));
        assertTrue(c.createFeedback(LlmRequestLogFeedback.builder().build()).isEmpty());
        assertTrue(c.createFeedbackAsync(LlmRequestLogFeedback.builder().build()).join().isEmpty());
    }

    @Test
    void sanitizeHeadersIsExposed() {
        assertEquals(Map.of("authorization", "[REDACTED]"), coolhand().sanitizeHeaders(Map.of("Authorization", "x"), null));
    }

    @Test
    void globalMonitoringIsInstalledOnceAndReset() {
        assertFalse(Coolhand.isGlobalMonitoringActive());
        assertEquals(new CoolhandStats(0, 0, "Not initialized"), Coolhand.getGlobalStats());
        CoolhandOptions options = CoolhandOptions.builder().apiKey("k").baseUrl(api.baseUrl()).build();
        Coolhand first = Coolhand.initializeGlobalMonitoring(options);
        assertSame(first, Coolhand.initializeGlobalMonitoring(options));
        assertSame(first, Coolhand.global().orElseThrow());
        assertTrue(Coolhand.isConfigured());
        assertTrue(Coolhand.isGlobalMonitoringActive());
        Coolhand.resetGlobalMonitoring();
        assertFalse(Coolhand.isGlobalMonitoringActive());
    }

    @Test
    void optionsFromTheEnvironment() {
        CoolhandOptions o = CoolhandOptions.fromEnv(Map.of("COOLHAND_API_KEY", "k", "COOLHAND_SILENT", "false",
                "COOLHAND_DEBUG", "true", "COOLHAND_DRY_RUN", "true", "COOLHAND_BASE_URL", "http://localhost:3000",
                "COOLHAND_EXCLUDE_API_PATTERNS", " /a/ , ,/b/", "COOLHAND_PATTERNS_FILE", "/tmp/p.json"));
        assertEquals("k", o.apiKey());
        assertFalse(o.silent());
        assertTrue(o.debug());
        assertTrue(o.dryRun());
        assertEquals("http://localhost:3000", o.baseUrl());
        assertEquals(List.of("/a/", "/b/"), o.excludeApiPatterns());
        assertEquals("/tmp/p.json", o.patternsFile().toString());

        CoolhandOptions defaults = CoolhandOptions.fromEnv(Map.of("COOLHAND_EXCLUDE_API_PATTERNS", " , "));
        assertFalse(defaults.hasApiKey());
        assertTrue(defaults.silent());
        assertEquals(null, defaults.excludeApiPatterns());
        assertEquals(Duration.ofSeconds(30), defaults.captureIdleTimeout());
    }

    @Test
    void dryRunSubmitsNothing() {
        Coolhand c = new Coolhand(CoolhandOptions.builder().apiKey("k").baseUrl(api.baseUrl()).dryRun(true).build());
        assertTrue(c.logRequest(CallData.builder().url("https://x").build()).isEmpty());
        assertTrue(c.createFeedback(LlmRequestLogFeedback.builder().build()).isEmpty());
        assertEquals(null, api.poll(Duration.ofMillis(300)));
    }
}
