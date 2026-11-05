package com.coolhandlabs.coolhand.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.integrations.openai.CoolhandOpenAI;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAIIntegrationTest {
    private static final String COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello\","
            + "\"refusal\":null},\"finish_reason\":\"stop\",\"logprobs\":null}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    private final FakeServer provider = new FakeServer();
    private final FakeServer coolhandApi = new FakeServer();
    private Coolhand coolhand;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        coolhand = TestSupport.coolhand(coolhandApi, TestSupport.localPatterns(dir, "OpenAI", "authorization"));
    }

    @AfterEach
    void tearDown() {
        provider.close();
        coolhandApi.close();
    }

    @Test
    void logsAChatCompletion() {
        provider.on("/v1/chat/completions", FakeServer.json(200, COMPLETION));
        OpenAIClient client = CoolhandOpenAI.client(coolhand,
                b -> b.apiKey("sk-openai-secret").baseUrl(provider.baseUrl() + "/v1").maxRetries(0));
        ChatCompletion completion = Coolhand.withContext(DeclaredContext.builder().reporter("openai").build(),
                () -> client.chat().completions().create(ChatCompletionCreateParams.builder()
                        .model("gpt-test").addUserMessage("hi").build()));
        assertEquals("chatcmpl-1", completion.id());
        assertEquals("Bearer sk-openai-secret", provider.take().header("Authorization"));

        JsonNode log = TestSupport.normalizedLog(coolhandApi.take().json());
        JsonNode raw = log.at("/llm_request_log/raw_request");
        assertEquals(provider.baseUrl() + "/v1/chat/completions", raw.get("url").asText());
        assertEquals("[REDACTED]", raw.at("/headers/authorization").asText());
        assertEquals(TestSupport.json("{\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"gpt-test\"}"),
                raw.get("request_body"));
        assertEquals(TestSupport.json(COMPLETION), raw.get("response_body"));
        assertTrue(raw.get("stream_incomplete") == null);
        assertEquals("openai", log.at("/llm_request_log/declared/reporter").asText());
    }
}
