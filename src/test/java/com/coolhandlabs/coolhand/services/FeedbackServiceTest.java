package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.CollectionMethod;
import com.coolhandlabs.coolhand.CoolhandHttpException;
import com.coolhandlabs.coolhand.CreatorType;
import com.coolhandlabs.coolhand.FeedbackResponse;
import com.coolhandlabs.coolhand.LlmRequestLogFeedback;
import com.coolhandlabs.coolhand.Sentiment;
import com.coolhandlabs.coolhand.Version;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchFeedbackParams;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeedbackServiceTest {
    private final FakeServer api = new FakeServer();

    @AfterEach
    void close() {
        api.close();
    }

    private FeedbackService service() {
        return new FeedbackService(ServiceConfig.builder().apiKey(TestSupport.API_KEY).baseUrl(api.baseUrl()).build());
    }

    @Test
    void postsTheExactPayloadAndParsesTheResponse() {
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(201, "{\"id\":\"fb1\",\"llm_request_log_id\":\"log9\","
                + "\"sentiment\":\"dislike\",\"creator_type\":\"human\",\"warnings\":[{\"message\":\"w\",\"timestamp\":\"t\"}],"
                + "\"created_at\":\"c\",\"updated_at\":\"u\"}"));
        Optional<FeedbackResponse> result = service().createFeedback(LlmRequestLogFeedback.builder()
                .llmProviderUniqueId("msg_01").sentiment(Sentiment.DISLIKE)
                .explanation("Missing the boundary case").creatorType(CreatorType.HUMAN).build(), CollectionMethod.MANUAL);

        FakeServer.Received posted = api.take();
        assertEquals("POST", posted.method());
        assertEquals(TestSupport.API_KEY, posted.header("X-API-Key"));
        assertEquals(TestSupport.json("{\"llm_request_log_feedback\":{\"sentiment\":\"dislike\",\"creator_type\":\"human\","
                + "\"explanation\":\"Missing the boundary case\",\"llm_provider_unique_id\":\"msg_01\","
                + "\"collector\":\"coolhand-java-" + Version.PACKAGE_VERSION + "-manual\"}}"), posted.json());
        FeedbackResponse r = result.orElseThrow();
        assertEquals("fb1", r.id());
        assertEquals("log9", r.llmRequestLogId());
        assertEquals(Sentiment.DISLIKE, r.sentiment());
        assertEquals(CreatorType.HUMAN, r.creatorType());
        assertEquals(java.util.List.of("w"), r.warnings());
    }

    @Test
    @SuppressWarnings("deprecation")
    void convertsTheDeprecatedLikeFlagWhenNoSentimentIsGiven() {
        service().createFeedback(LlmRequestLogFeedback.builder().llmRequestLogId(12).like(true).build(), CollectionMethod.MANUAL);
        JsonNode fb = api.take().json().get("llm_request_log_feedback");
        assertEquals(12, fb.get("llm_request_log_id").intValue());
        assertEquals("like", fb.get("sentiment").asText());
        assertTrue(fb.get("like") == null);

        service().createFeedback(LlmRequestLogFeedback.builder().like(false).sentiment(Sentiment.NEUTRAL).build(), null);
        JsonNode both = api.take().json().get("llm_request_log_feedback");
        assertEquals(false, both.get("like").booleanValue());
        assertEquals("neutral", both.get("sentiment").asText());
        assertEquals("coolhand-java-" + Version.PACKAGE_VERSION, both.get("collector").asText());
    }

    @Test
    void numericLogIdsInResponsesBecomeText() {
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(201, "{\"id\":\"fb\",\"llm_request_log_id\":77}"));
        assertEquals("77", service().createFeedback(LlmRequestLogFeedback.builder().build(), null).orElseThrow().llmRequestLogId());
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(201, "{\"id\":\"fb\",\"llm_request_log_id\":null}"));
        assertNull(service().createFeedback(LlmRequestLogFeedback.builder().build(), null).orElseThrow().llmRequestLogId());
    }

    @Test
    void neverThrowsOnFailure() {
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(422, "{\"error\":\"bad\"}"));
        assertTrue(service().createFeedback(LlmRequestLogFeedback.builder().build(), null).isEmpty());
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.raw(201, "text/plain", "not json".getBytes(), java.util.Map.of()));
        assertTrue(service().createFeedback(LlmRequestLogFeedback.builder().build(), null).isEmpty());
        FeedbackService unreachable = new FeedbackService(ServiceConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1")
                .requestTimeout(Duration.ofSeconds(2)).build());
        assertTrue(unreachable.createFeedback(LlmRequestLogFeedback.builder().build(), null).isEmpty());
        assertTrue(unreachable.createFeedbackAsync(LlmRequestLogFeedback.builder().build(), null).join().isEmpty());
    }

    @Test
    void searchWrapsPredicatesAndReadsTheBodyEnvelope() {
        api.on("/api/v2/llm_request_log_feedbacks", FakeServer.json(200, "{\"feedback\":[{\"id\":\"f1\"}],\"pagination\":{"
                + "\"current_page\":1,\"per_page\":25,\"total_count\":1,\"total_pages\":1,\"has_next_page\":false,\"has_prev_page\":false}}"));
        Page<JsonNode> page = service().searchFeedback(SearchFeedbackParams.builder().sentimentEq(2)
                .explanationCont("too long").sort("created_at desc").page(1).per(25).build());
        assertEquals("/api/v2/llm_request_log_feedbacks?q%5Bsentiment_eq%5D=2&q%5Bexplanation_cont%5D=too+long"
                + "&q%5Bs%5D=created_at+desc&page=1&per=25", api.take().target());
        assertEquals("f1", page.items().get(0).get("id").asText());
        assertEquals(1, page.pagination().totalCount());
    }

    @Test
    void getFeedbackValidatesAndThrowsWithStatus() {
        assertEquals("getFeedback: id must be a non-empty string",
                assertThrows(IllegalArgumentException.class, () -> service().getFeedback("")).getMessage());
        api.on("/api/v2/llm_request_log_feedbacks/", FakeServer.json(404, "{}"));
        assertEquals(404, assertThrows(CoolhandHttpException.class, () -> service().getFeedback("zz")).status());
        assertEquals("/api/v2/llm_request_log_feedbacks/zz", api.take().target());
    }
}
