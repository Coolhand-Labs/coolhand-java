package com.coolhandlabs.coolhand.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.Version;
import com.coolhandlabs.coolhand.integrations.okhttp.CoolhandInterceptor;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OkHttpIntegrationTest {
    private static final MediaType JSON = MediaType.get("application/json");
    private final FakeServer provider = new FakeServer();
    private final FakeServer coolhandApi = new FakeServer();
    private Coolhand coolhand;
    private OkHttpClient http;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        coolhand = TestSupport.coolhand(coolhandApi, TestSupport.localPatterns(dir, "OpenAI", "authorization"));
        http = new OkHttpClient.Builder().addInterceptor(new CoolhandInterceptor(coolhand)).build();
    }

    @AfterEach
    void tearDown() {
        provider.close();
        coolhandApi.close();
    }

    private Request.Builder chat(String body) {
        return new Request.Builder().url(provider.baseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer sk-live")
                .post(RequestBody.create(body, JSON));
    }

    @Test
    void logsTheExactPayload() throws IOException {
        String completion = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}";
        provider.on("/v1/chat/completions", FakeServer.json(200, completion));
        String body = "{\"model\":\"gpt-test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String received;
        try (Response response = Coolhand.withContext(DeclaredContext.builder().reporter("okhttp").build(),
                () -> http.newCall(chat(body).build()).execute())) {
            received = response.body().string();
        }
        assertEquals(completion, received);
        assertEquals(body, provider.take().text());

        JsonNode log = TestSupport.normalizedLog(coolhandApi.take().json());
        JsonNode raw = log.at("/llm_request_log/raw_request");
        assertEquals(TestSupport.json("{\"authorization\":\"[REDACTED]\",\"content-type\":\"application/json; charset=utf-8\"}"),
                raw.get("headers"));
        assertEquals(TestSupport.json(body), raw.get("request_body"));
        assertEquals(TestSupport.json(completion), raw.get("response_body"));
        assertEquals(200, raw.get("status_code").intValue());
        assertEquals("http", raw.get("protocol").asText());
        assertEquals(provider.baseUrl() + "/v1/chat/completions", raw.get("url").asText());
        assertEquals("coolhand-java-" + Version.PACKAGE_VERSION + "-global-monitoring",
                log.at("/llm_request_log/collector").asText());
        assertEquals(TestSupport.json("{\"reporter\":\"okhttp\"}"), log.at("/llm_request_log/declared"));
    }

    @Test
    void enqueueCarriesATaggedContext() throws Exception {
        provider.on("/v1/chat/completions", FakeServer.json(200, "{}"));
        CompletableFuture<Integer> status = new CompletableFuture<>();
        Coolhand.withContext(DeclaredContext.builder().reporter("tagged").build(), () ->
                http.newCall(CoolhandInterceptor.tagCurrentContext(chat("{}")).build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        status.completeExceptionally(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (response) {
                            response.body().string();
                            status.complete(response.code());
                        }
                    }
                }));
        assertEquals(200, status.get());
        assertEquals("tagged", coolhandApi.take().json().at("/llm_request_log/declared/reporter").asText());
    }

    @Test
    void streamsServerSentEventsThroughAndLogsOnceComplete() throws IOException {
        provider.on("/v1/chat/completions", FakeServer.sse(HttpClientIntegrationTest.SSE, Duration.ofMillis(10)));
        try (Response response = http.newCall(chat("{\"stream\":true}").build()).execute()) {
            assertEquals(String.join("", HttpClientIntegrationTest.SSE), response.body().string());
        }
        JsonNode raw = coolhandApi.take().json().at("/llm_request_log/raw_request");
        assertEquals(String.join("", HttpClientIntegrationTest.SSE), raw.get("response_body").textValue());
        assertTrue(raw.get("stream_incomplete") == null);
    }

    @Test
    void closingAStreamEarlyLogsItAsIncomplete() throws IOException {
        provider.on("/v1/chat/completions", FakeServer.sse(HttpClientIntegrationTest.SSE, Duration.ofMillis(300)));
        try (Response response = http.newCall(chat("{\"stream\":true}").build()).execute()) {
            InputStream in = response.body().byteStream();
            assertTrue(in.read(new byte[32]) > 0);
        }
        JsonNode raw = coolhandApi.take().json().at("/llm_request_log/raw_request");
        assertTrue(raw.get("stream_incomplete").booleanValue());
        assertFalse(raw.get("response_body").textValue().contains("message_stop"));
    }

    @Test
    void closingAJsonResponseUnreadStillLogsTheWholeBody() throws IOException {
        provider.on("/v1/chat/completions", FakeServer.json(200, "{\"id\":\"x\"}"));
        try (Response response = http.newCall(chat("{}").build()).execute()) {
            assertEquals(200, response.code());
        }
        JsonNode raw = coolhandApi.take().json().at("/llm_request_log/raw_request");
        assertEquals("x", raw.at("/response_body/id").asText());
        assertTrue(raw.get("stream_incomplete") == null);
    }

    @Test
    void otherHostsPassThrough() throws IOException {
        provider.on("/v1/chat/completions", FakeServer.json(200, "{}"));
        Request request = new Request.Builder().url("http://localhost:" + provider.port() + "/v1/chat/completions").build();
        try (Response response = http.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertTrue(coolhand.flush(Duration.ofSeconds(2)));
        assertNull(coolhandApi.poll(Duration.ofMillis(300)));
    }
}
