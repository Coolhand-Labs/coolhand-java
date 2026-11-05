package com.coolhandlabs.coolhand.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.CoolhandOptions;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.Version;
import com.coolhandlabs.coolhand.integrations.anthropic.CoolhandAnthropic;
import com.coolhandlabs.coolhand.integrations.anthropic.CoolhandAnthropicHttpClient;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.coolhandlabs.coolhand.testing.CoolhandTestUtils;
import com.coolhandlabs.coolhand.testing.InterceptionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnthropicIntegrationTest {
    private final FakeServer provider = new FakeServer();
    private final FakeServer coolhandApi = new FakeServer();
    private Path patterns;
    private Coolhand coolhand;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        patterns = TestSupport.localPatterns(dir, "Anthropic", "x-api-key");
        coolhand = TestSupport.coolhand(coolhandApi, patterns);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        coolhandApi.close();
        Coolhand.resetGlobalMonitoring();
    }

    private AnthropicClient client(Coolhand monitor) {
        return CoolhandAnthropic.client(monitor, b -> b.apiKey("sk-ant-secret").baseUrl(provider.baseUrl()).maxRetries(0));
    }

    private static MessageCreateParams params() {
        return MessageCreateParams.builder().model("claude-test").maxTokens(100).addUserMessage("hi").build();
    }

    @Test
    void logsAMessagesCallWithItsDeclaredContext() {
        provider.on("/v1/messages", FakeServer.json(200, HttpClientIntegrationTest.MESSAGE, java.util.Map.of("request-id", "req_9")));
        AnthropicClient client = client(coolhand);
        InterceptionSnapshot before = CoolhandTestUtils.captureInterceptionSnapshot(coolhand);
        Message message = Coolhand.withContext(DeclaredContext.builder().reporter("support-bot")
                .workload("support/triage", "Triage", "Routes tickets").template("support/triage/classify", "Classify").build(),
                () -> client.messages().create(params()));
        assertEquals("msg_01", message.id());
        CoolhandTestUtils.assertInterceptionOccurred(coolhand, before);

        JsonNode log = TestSupport.normalizedLog(coolhandApi.take().json());
        JsonNode raw = log.at("/llm_request_log/raw_request");
        assertEquals("POST", raw.get("method").asText());
        assertEquals(provider.baseUrl() + "/v1/messages", raw.get("url").asText());
        FakeServer.Received sent = provider.take();
        assertEquals("Bearer sk-ant-secret", sent.header("Authorization"));
        assertEquals("[REDACTED]", raw.at("/headers/authorization").asText());
        assertFalse(log.toString().contains("sk-ant-secret"));
        assertEquals(sent.header("anthropic-version"), raw.at("/headers/anthropic-version").asText());
        assertEquals(TestSupport.json("{\"max_tokens\":100,\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],"
                + "\"model\":\"claude-test\"}"), raw.get("request_body"));
        assertEquals(TestSupport.json(HttpClientIntegrationTest.MESSAGE), raw.get("response_body"));
        assertEquals("req_9", raw.at("/response_headers/request-id").asText());
        assertEquals(200, raw.get("status_code").intValue());
        assertTrue(raw.get("stream_incomplete") == null);
        assertEquals("coolhand-java-" + Version.PACKAGE_VERSION + "-global-monitoring",
                log.at("/llm_request_log/collector").asText());
        assertEquals(TestSupport.json("{\"reporter\":\"support-bot\",\"workload\":{\"key\":\"support/triage\",\"name\":\"Triage\","
                + "\"description\":\"Routes tickets\"},\"template\":{\"key\":\"support/triage/classify\",\"name\":\"Classify\"}}"),
                log.at("/llm_request_log/declared"));
    }

    @Test
    void logsAStreamedMessageOnceTheStreamIsRead() {
        provider.on("/v1/messages", FakeServer.sse(HttpClientIntegrationTest.SSE, Duration.ofMillis(5)));
        AtomicInteger events = new AtomicInteger();
        try (StreamResponse<RawMessageStreamEvent> stream = client(coolhand).messages().createStreaming(params())) {
            stream.stream().forEach(e -> events.incrementAndGet());
        }
        assertEquals(HttpClientIntegrationTest.SSE.size(), events.get());
        JsonNode raw = coolhandApi.take().json().at("/llm_request_log/raw_request");
        assertEquals(String.join("", HttpClientIntegrationTest.SSE), raw.get("response_body").textValue());
        assertTrue(raw.at("/request_body/stream").booleanValue());
        assertTrue(raw.get("stream_incomplete") == null, raw.toString());
    }

    @Test
    void asyncCallsAreLoggedToo() throws Exception {
        provider.on("/v1/messages", FakeServer.json(200, HttpClientIntegrationTest.MESSAGE));
        Message message = client(coolhand).async().messages().create(params()).get();
        assertEquals("msg_01", message.id());
        assertEquals("msg_01", coolhandApi.take().json().at("/llm_request_log/raw_request/response_body/id").asText());
    }

    @Test
    void errorResponsesAreLoggedAndStillThrowInTheSdk() {
        provider.on("/v1/messages", FakeServer.json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}"));
        try {
            client(coolhand).messages().create(params());
        } catch (RuntimeException expected) {
            // the SDK's BadRequestException
        }
        JsonNode raw = coolhandApi.take().json().at("/llm_request_log/raw_request");
        assertEquals(400, raw.get("status_code").intValue());
        assertEquals("bad", raw.at("/response_body/error/message").asText());
    }

    @Test
    void usesTheGlobalClientAndIsPlainWithoutOne() {
        provider.on("/v1/messages", FakeServer.json(200, HttpClientIntegrationTest.MESSAGE));
        // no global client and no COOLHAND_API_KEY: a plain, unmonitored client
        assertTrue(Coolhand.global().isEmpty());
        assertEquals("msg_01", CoolhandAnthropic.client(b -> b.apiKey("k").baseUrl(provider.baseUrl()).maxRetries(0))
                .messages().create(params()).id());
        assertNull(coolhandApi.poll(Duration.ofMillis(300)));

        Coolhand.initializeGlobalMonitoring(CoolhandOptions.builder().apiKey(TestSupport.API_KEY)
                .baseUrl(coolhandApi.baseUrl()).patternsFile(patterns).build());
        CoolhandAnthropic.client(b -> b.apiKey("k").baseUrl(provider.baseUrl()).maxRetries(0)).messages().create(params());
        assertEquals("msg_01", coolhandApi.take().json().at("/llm_request_log/raw_request/response_body/id").asText());
        assertTrue(CoolhandAnthropic.interceptor().intercept(new NoopClient()) instanceof CoolhandAnthropicHttpClient);
        assertTrue(CoolhandAnthropic.wrap(new NoopClient()) instanceof CoolhandAnthropicHttpClient);
    }

    /** A transport that is never called; only its wrapping is checked. */
    private static final class NoopClient implements com.anthropic.core.http.HttpClient {
        @Override
        public com.anthropic.core.http.HttpResponse execute(com.anthropic.core.http.HttpRequest request,
                com.anthropic.core.RequestOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletableFuture<com.anthropic.core.http.HttpResponse> executeAsync(
                com.anthropic.core.http.HttpRequest request, com.anthropic.core.RequestOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }
}
