package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.CallData;
import com.coolhandlabs.coolhand.CollectionMethod;
import com.coolhandlabs.coolhand.CoolhandException;
import com.coolhandlabs.coolhand.CoolhandHttpException;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.DeclaredWorkload;
import com.coolhandlabs.coolhand.LogResponse;
import com.coolhandlabs.coolhand.Version;
import com.coolhandlabs.coolhand.model.GetLogContentOptions;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchLogsParams;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LoggingServiceTest {
    private final FakeServer api = new FakeServer();

    @AfterEach
    void close() {
        api.close();
    }

    private LoggingService service(boolean dryRun) {
        return new LoggingService(ServiceConfig.builder().apiKey(TestSupport.API_KEY).baseUrl(api.baseUrl())
                .dryRun(dryRun).build());
    }

    private static CallData call(boolean incomplete) {
        return CallData.builder().id(7).timestamp("2026-01-01T00:00:00.000Z").method("POST")
                .url("https://api.test.com/v1/test").headers(Map.of()).requestBody(TestSupport.json("{\"prompt\":\"hi\"}"))
                .responseBody(TestSupport.json("{\"ok\":true}")).responseHeaders(Map.of()).statusCode(200)
                .protocol("https").streamIncomplete(incomplete).build();
    }

    @Test
    void postsTheExactPayload() {
        api.on("/api/v2/llm_request_logs", FakeServer.json(201, "{\"id\":42,\"llm_provider_unique_id\":\"msg_1\"}"));
        Optional<LogResponse> result = service(false).logRequestToApi(call(false), null, CollectionMethod.MANUAL, null,
                null, null).join();

        FakeServer.Received posted = api.take();
        assertEquals("POST", posted.method());
        assertEquals("/api/v2/llm_request_logs", posted.path());
        assertEquals(TestSupport.API_KEY, posted.header("X-API-Key"));
        assertEquals("application/json", posted.header("Content-Type"));
        assertEquals("coolhand-java/" + Version.PACKAGE_VERSION, posted.header("User-Agent"));
        assertEquals(TestSupport.json("{\"llm_request_log\":{\"raw_request\":{\"id\":7,\"timestamp\":\"2026-01-01T00:00:00.000Z\","
                + "\"method\":\"POST\",\"url\":\"https://api.test.com/v1/test\",\"headers\":{},\"request_body\":{\"prompt\":\"hi\"},"
                + "\"response_body\":{\"ok\":true},\"response_headers\":{},\"status_code\":200,\"protocol\":\"https\"},"
                + "\"collector\":\"coolhand-java-" + Version.PACKAGE_VERSION + "-manual\"}}"), posted.json());
        assertEquals("42", result.orElseThrow().id());
        assertEquals("msg_1", result.orElseThrow().llmProviderUniqueId());
    }

    @Test
    void sendsStreamIncompleteOnlyAsATrueBoolean() {
        service(false).logRequestToApi(call(true), null, CollectionMethod.MANUAL, null, null, null).join();
        assertTrue(api.take().json().at("/llm_request_log/raw_request/stream_incomplete").booleanValue());
        service(false).logRequestToApi(call(false), null, CollectionMethod.MANUAL, null, null, null).join();
        assertTrue(api.take().json().at("/llm_request_log/raw_request/stream_incomplete").isMissingNode());
    }

    @Test
    void anExplicitCollectorOverridesAndMetadataAndDeclaredAreSiblings() {
        DeclaredContext declared = DeclaredContext.builder().reporter("r").workload(DeclaredWorkload.ofKey("w")).build();
        service(false).logRequestToApi(call(false), null, CollectionMethod.MANUAL, "coolhand-cli-1.0.0",
                Map.of("project_path", "/Users/me/p"), declared).join();
        JsonNode log = api.take().json().get("llm_request_log");
        assertEquals("coolhand-cli-1.0.0", log.get("collector").asText());
        assertEquals("/Users/me/p", log.at("/metadata/project_path").asText());
        assertEquals(TestSupport.json("{\"reporter\":\"r\",\"workload\":{\"key\":\"w\"}}"), log.get("declared"));
        assertEquals("[raw_request, collector, metadata, declared]", fieldNames(log));
    }

    private static String fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    @Test
    void failuresResolveToEmptyNeverThrow() {
        api.on("/api/v2/llm_request_logs", FakeServer.json(500, "{\"error\":\"boom " + TestSupport.API_KEY + "\"}"));
        assertTrue(service(false).logRequestToApi(call(false), null, null, null, null, null).join().isEmpty());
        LoggingService unreachable = new LoggingService(ServiceConfig.builder().apiKey("k")
                .baseUrl("http://127.0.0.1:1").requestTimeout(Duration.ofSeconds(2)).build());
        assertTrue(unreachable.logRequestToApi(call(false), null, null, null, null, null).join().isEmpty());
    }

    @Test
    void dryRunSendsNothing() {
        assertTrue(service(true).logRequestToApi(call(false), null, CollectionMethod.MANUAL, null, null, null).join().isEmpty());
        assertNull(api.poll(Duration.ofMillis(300)));
    }

    @Test
    void readsServerWarnings() {
        api.on("/api/v2/llm_request_logs", FakeServer.json(201, "{\"id\":1,\"warnings\":[{\"message\":\"cap reached\"},\"plain\"]}"));
        LogResponse r = service(false).logRequestToApi(call(false), null, null, null, null, null).join().orElseThrow();
        assertEquals(java.util.List.of("cap reached", "plain"), r.warnings());
    }

    @Test
    void getLogContentSendsOptionsAsQueryParams() {
        api.on("/api/v2/llm_request_logs/", FakeServer.json(200, "{\"id\":\"abc\",\"output\":\"x\"}"));
        JsonNode content = service(false).getLogContent("abc", GetLogContentOptions.builder()
                .section(GetLogContentOptions.Section.END).maxChars(500).includeThinking(true).includeTurnMetrics(false).build());
        FakeServer.Received r = api.take();
        assertEquals("GET", r.method());
        assertEquals("/api/v2/llm_request_logs/abc?section=end&max_chars=500&include_thinking=true&include_turn_metrics=false",
                r.target());
        assertEquals("application/json", r.header("Accept"));
        assertEquals("x", content.get("output").asText());

        service(false).getLogContent("a/b c", GetLogContentOptions.builder().searchQuery("needle here").build());
        assertEquals("/api/v2/llm_request_logs/a%2Fb%20c?search_query=needle+here", api.take().target());
    }

    @Test
    void getLogContentValidatesBeforeAnyRequest() {
        LoggingService s = service(false);
        assertEquals("getLogContent: logId must be a non-empty string",
                assertThrows(IllegalArgumentException.class, () -> s.getLogContent(" ", null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> s.getLogContent("..", null));
        assertThrows(IllegalArgumentException.class, () -> s.getLogContent(".", null));
        assertEquals("getLogContent: searchQuery must be a non-empty string", assertThrows(IllegalArgumentException.class,
                () -> s.getLogContent("abc", GetLogContentOptions.builder().searchQuery("  ").build())).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> GetLogContentOptions.builder().searchQuery("x").maxChars(3).build());
        assertNull(api.poll(Duration.ofMillis(200)));
    }

    @Test
    void readErrorsCarryTheStatusAndNeverTheKey() {
        api.on("/api/v2/llm_request_logs/", FakeServer.json(404, "{\"error\":\"no " + TestSupport.API_KEY + "\"}"));
        CoolhandHttpException e = assertThrows(CoolhandHttpException.class, () -> service(false).getLogContent("nope", null));
        assertEquals(404, e.status());
        assertEquals("Log request failed (404): {\"error\":\"no [redacted]\"}", e.getMessage());

        api.on("/api/v2/llm_request_logs/", FakeServer.raw(200, "text/html", "<html>".getBytes(), Map.of()));
        assertEquals("Log response was not valid JSON: <html>",
                assertThrows(CoolhandException.class, () -> service(false).getLogContent("x", null)).getMessage());

        LoggingService unreachable = new LoggingService(ServiceConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1").build());
        CoolhandException network = assertThrows(CoolhandException.class, () -> unreachable.getLogContent("x", null));
        assertFalse(network instanceof CoolhandHttpException);
        assertTrue(network.getMessage().startsWith("Log request failed: "), network.getMessage());
    }

    @Test
    void redirectsAreNeverFollowed() {
        api.on("/api/v2/llm_request_logs", (req, ex) -> {
            ex.getResponseHeaders().add("Location", "https://evil.example/steal");
            ex.sendResponseHeaders(302, -1);
        });
        CoolhandException e = assertThrows(CoolhandException.class, () -> service(false).searchLogs(null));
        assertEquals("Log request failed: redirect (302) not followed", e.getMessage());
        assertEquals(1, api.drain().size());
    }

    @Test
    void searchLogsBuildsNamedFiltersAndReadsPagination() {
        api.on("/api/v2/llm_request_logs", FakeServer.json(200, "[{\"id\":\"a\",\"cost\":null},{\"id\":\"b\",\"cost\":0.5}]",
                Map.of("X-Page", "2", "X-Per-Page", "2", "X-Total-Count", "5", "X-Total-Pages", "3")));
        Page<JsonNode> page = service(false).searchLogs(SearchLogsParams.builder().templateId("t1").workloadId("w1")
                .model("claude").unmatchedOnly(false).since(Instant.parse("2026-01-01T00:00:00Z")).until("2026-02-01T00:00:00+02:00")
                .minCost(0).order("cost_desc").sort("created_at desc").page(2).per(2).includeTotal(true).build());
        assertEquals("/api/v2/llm_request_logs?template_id=t1&workload_id=w1&model=claude&unmatched_only=false"
                + "&since=2026-01-01T00%3A00%3A00.000Z&until=2026-02-01T00%3A00%3A00%2B02%3A00&min_cost=0&order=cost_desc"
                + "&q%5Bs%5D=created_at+desc&page=2&per=2&include_total=true", api.take().target());
        assertEquals(2, page.items().size());
        assertTrue(page.items().get(0).get("cost").isNull());
        assertEquals(new com.coolhandlabs.coolhand.Pagination(2, 2, 5, 3, true, true), page.pagination());
    }
}
