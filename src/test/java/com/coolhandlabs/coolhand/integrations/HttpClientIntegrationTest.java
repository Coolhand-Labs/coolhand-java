package com.coolhandlabs.coolhand.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.Version;
import com.coolhandlabs.coolhand.integrations.httpclient.CoolhandHttpClient;
import com.coolhandlabs.coolhand.internal.Json;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpClientIntegrationTest {
    static final String MESSAGE = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}";
    static final List<String> SSE = List.of(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_s\",\"type\":\"message\","
                    + "\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n",
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                    + "\"usage\":{\"output_tokens\":2}}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");

    private final FakeServer provider = new FakeServer();
    private final FakeServer coolhandApi = new FakeServer();
    private Coolhand coolhand;
    private HttpClient http;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        coolhand = TestSupport.coolhand(coolhandApi, TestSupport.localPatterns(dir, "Anthropic", "x-api-key"),
                Duration.ofMillis(800));
        http = CoolhandHttpClient.wrap(HttpClient.newHttpClient(), coolhand);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        coolhandApi.close();
    }

    private HttpRequest.Builder messages(String body) {
        return HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/v1/messages?beta=true"))
                .header("x-api-key", "sk-ant-secret")
                .header("content-type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private JsonNode loggedCall() {
        return coolhandApi.take().json();
    }

    @Test
    void logsTheExactPayloadForACompletedCall() throws Exception {
        provider.on("/v1/messages", FakeServer.json(200, MESSAGE, Map.of("request-id", "req_1")));
        String requestBody = "{\"model\":\"claude-test\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HttpResponse<String> response = Coolhand.withContext(
                DeclaredContext.builder().reporter("support-bot").workload("support/triage", "Triage", "Routes tickets").template("support/triage/v1", "V1").build(),
                () -> http.send(messages(requestBody).build(), HttpResponse.BodyHandlers.ofString()));

        assertEquals(MESSAGE, response.body());
        FakeServer.Received atProvider = provider.take();
        assertEquals(requestBody, atProvider.text());
        assertEquals("sk-ant-secret", atProvider.header("x-api-key"));

        JsonNode expected = TestSupport.json("{\"llm_request_log\":{"
                + "\"raw_request\":{\"id\":1,\"timestamp\":\"<timestamp>\",\"method\":\"POST\","
                + "\"url\":\"" + provider.baseUrl() + "/v1/messages?beta=true\","
                + "\"headers\":{\"anthropic-version\":\"2023-06-01\",\"content-type\":\"application/json\",\"x-api-key\":\"[REDACTED]\"},"
                + "\"request_body\":" + requestBody + ","
                + "\"response_body\":" + MESSAGE + ","
                + "\"response_headers\":{\"content-length\":\"" + MESSAGE.length() + "\",\"content-type\":\"application/json\","
                + "\"date\":\"<date>\",\"request-id\":\"req_1\"},"
                + "\"status_code\":200,\"protocol\":\"http\"},"
                + "\"collector\":\"coolhand-java-" + Version.PACKAGE_VERSION + "-global-monitoring\","
                + "\"declared\":{\"reporter\":\"support-bot\",\"workload\":{\"key\":\"support/triage\",\"name\":\"Triage\",\"description\":\"Routes tickets\"},"
                + "\"template\":{\"key\":\"support/triage/v1\",\"name\":\"V1\"}}}}");
        assertEquals(expected, TestSupport.normalizedLog(loggedCall()));
        assertEquals(1, coolhand.getStats().interceptedCalls());
    }

    @Test
    void teesAStreamedResponseToTheEndAndLogsItOnce() throws Exception {
        provider.on("/v1/messages", FakeServer.sse(SSE, Duration.ofMillis(20)));
        HttpResponse<java.util.stream.Stream<String>> response = http.send(messages("{\"stream\":true}").build(),
                HttpResponse.BodyHandlers.ofLines());
        long lines = response.body().count();
        assertEquals(SSE.size() * 3, lines);

        JsonNode raw = loggedCall().at("/llm_request_log/raw_request");
        assertEquals(String.join("", SSE), raw.get("response_body").textValue());
        assertTrue(raw.get("stream_incomplete") == null);
        assertEquals("text/event-stream", raw.at("/response_headers/content-type").asText());
        assertNull(coolhandApi.poll(Duration.ofMillis(300)));
    }

    @Test
    void anAbandonedStreamIsLoggedAsIncompleteWithThePartialBody() throws Exception {
        provider.on("/v1/messages", FakeServer.sse(SSE, Duration.ofMillis(300)));
        HttpResponse<InputStream> response = http.send(messages("{\"stream\":true}").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] first = new byte[64];
            assertTrue(in.read(first) > 0);
        }
        JsonNode raw = loggedCall().at("/llm_request_log/raw_request");
        assertTrue(raw.get("stream_incomplete").booleanValue());
        String partial = raw.get("response_body").textValue();
        assertTrue(partial.startsWith("event: message_start"), partial);
        assertFalse(partial.contains("message_stop"));
    }

    @Test
    void aParserThatStopsAtTheEndOfTheJsonStillLogsACompleteCall() throws Exception {
        provider.on("/v1/messages", FakeServer.json(200, MESSAGE + "\n"));
        HttpResponse<InputStream> response = http.send(messages("{}").build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            assertEquals("msg_01", Json.MAPPER.readTree(new java.io.BufferedInputStream(in, 16)).get("id").asText());
        }
        JsonNode raw = loggedCall().at("/llm_request_log/raw_request");
        assertTrue(raw.get("stream_incomplete") == null);
        assertEquals("msg_01", raw.at("/response_body/id").asText());
    }

    @Test
    void decompressesGzipForTheLogButHandsTheHostTheOriginalBytes() throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(MESSAGE.getBytes(StandardCharsets.UTF_8));
        }
        provider.on("/v1/messages", FakeServer.raw(200, "application/json", gz.toByteArray(), Map.of("Content-Encoding", "gzip")));
        HttpResponse<byte[]> response = http.send(messages("{}").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(gz.size(), response.body().length);
        assertEquals("msg_01", loggedCall().at("/llm_request_log/raw_request/response_body/id").asText());
    }

    @Test
    void streamingRequestBodiesAreCapturedAndSentUnchanged() throws Exception {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(20_000) + "\"}],\"api_key\":\"leak\"}";
        HttpRequest request = messages("").POST(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))).build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(body, provider.take().text());
        JsonNode logged = loggedCall().at("/llm_request_log/raw_request/request_body");
        assertEquals(20_000, logged.at("/messages/0/content").asText().length());
        assertEquals("[REDACTED]", logged.get("api_key").asText());
    }

    @Test
    void sendAsyncSnapshotsTheContextOnTheCallingThread() throws Exception {
        provider.on("/v1/messages", FakeServer.json(200, MESSAGE));
        HttpResponse<String> response = Coolhand.withContext(DeclaredContext.builder().reporter("async").build(),
                () -> http.sendAsync(messages("{}").build(), HttpResponse.BodyHandlers.ofString())).get();
        assertEquals(200, response.statusCode());
        assertEquals("async", loggedCall().at("/llm_request_log/declared/reporter").asText());
    }

    @Test
    void nonLlmExcludedAndSelfRequestsPassThroughUnlogged() throws Exception {
        provider.on("/other", FakeServer.json(200, "{}"));
        // "localhost" is not in the patterns file, so this is not an LLM call
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + provider.port() + "/other")).build(),
                HttpResponse.BodyHandlers.ofString());
        coolhand.setExcludeApiPatterns(List.of("/v1/models"));
        http.send(HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/v1/models")).build(), HttpResponse.BodyHandlers.ofString());
        // the Coolhand API itself matches the pattern (127.0.0.1) but is never captured
        http.send(HttpRequest.newBuilder(URI.create(coolhandApi.baseUrl() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(1, coolhandApi.drain().size()); // only the /health request itself
        assertTrue(coolhand.flush(Duration.ofSeconds(2)));
        assertNull(coolhandApi.poll(Duration.ofMillis(300)));
        assertEquals(3, coolhand.getStats().totalRequests());
        assertEquals(0, coolhand.getStats().interceptedCalls());
        assertEquals(2, provider.drain().size());
    }

    @Test
    void aBodyTheHostNeverReadsIsLoggedAfterTheIdleTimeout() throws Exception {
        provider.on("/v1/messages", FakeServer.sse(SSE, Duration.ofMillis(10)));
        HttpResponse<InputStream> response = http.send(messages("{}").build(), HttpResponse.BodyHandlers.ofInputStream());
        JsonNode raw = coolhandApi.take(Duration.ofSeconds(10)).json().at("/llm_request_log/raw_request");
        assertTrue(raw.get("stream_incomplete").booleanValue());
        response.body().close();
    }

    @Test
    void wrapsLifecycleAndConfigurationOfTheDelegate() {
        HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpClient wrapped = CoolhandHttpClient.wrap(delegate, coolhand);
        assertEquals(delegate.connectTimeout(), wrapped.connectTimeout());
        assertEquals(delegate.version(), wrapped.version());
        assertEquals(delegate.followRedirects(), wrapped.followRedirects());
    }
}
