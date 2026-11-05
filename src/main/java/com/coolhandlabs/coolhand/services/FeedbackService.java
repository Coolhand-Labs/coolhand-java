package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.CollectionMethod;
import com.coolhandlabs.coolhand.FeedbackResponse;
import com.coolhandlabs.coolhand.LlmRequestLogFeedback;
import com.coolhandlabs.coolhand.Pagination;
import com.coolhandlabs.coolhand.Sentiment;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchFeedbackParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Creates feedback ({@code POST /api/v2/llm_request_log_feedbacks}) and reads it back. */
public final class FeedbackService extends BaseService {
    public FeedbackService(ServiceConfig config) {
        super(config, "/api/v2/llm_request_log_feedbacks");
    }

    /**
     * The wire fields of {@code feedback}, in a fixed order, with the deprecated {@code like} turned
     * into {@code sentiment} when no sentiment is given, and the collector string appended.
     */
    static Map<String, Object> toWire(LlmRequestLogFeedback feedback, CollectionMethod method) {
        Boolean like = feedback.like();
        Sentiment sentiment = feedback.sentiment();
        if (sentiment == null && like != null) {
            sentiment = like ? Sentiment.LIKE : Sentiment.DISLIKE;
            like = null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "llm_request_log_id", feedback.llmRequestLogId());
        put(out, "like", like);
        put(out, "sentiment", sentiment == null ? null : sentiment.wireName());
        put(out, "creator_type", feedback.creatorType() == null ? null : feedback.creatorType().wireName());
        put(out, "creator_unique_id", feedback.creatorUniqueId());
        put(out, "workload_hashid", feedback.workloadHashid());
        put(out, "explanation", feedback.explanation());
        put(out, "revised_output", feedback.revisedOutput());
        put(out, "llm_provider_unique_id", feedback.llmProviderUniqueId());
        put(out, "original_output", feedback.originalOutput());
        put(out, "client_unique_id", feedback.clientUniqueId());
        return withCollector(out, method);
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    /**
     * Creates feedback. The payload is {@code {"llm_request_log_feedback": {...fields, "collector": ...}}}.
     * Never throws for API or network failures: resolves to empty instead (and on a dry run).
     */
    public CompletableFuture<Optional<FeedbackResponse>> createFeedbackAsync(LlmRequestLogFeedback feedback,
            CollectionMethod collectionMethod) {
        try {
            Map<String, Object> fields = toWire(feedback, collectionMethod);
            logFeedbackInfo(fields);
            Object logId = feedback.llmRequestLogId();
            return sendRequestAsync(Map.of("llm_request_log_feedback", fields),
                    "✅ Successfully created feedback with ID: " + (logId == null || "".equals(logId) ? "N/A" : logId))
                    .thenApply(result -> {
                        logSeparator();
                        return result.filter(JsonNode::isObject).map(FeedbackResponse::fromJson);
                    })
                    .exceptionally(error -> Optional.empty());
        } catch (RuntimeException e) {
            com.coolhandlabs.coolhand.internal.Log.error("❌ Request error: " + describeError(e));
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    public Optional<FeedbackResponse> createFeedback(LlmRequestLogFeedback feedback, CollectionMethod collectionMethod) {
        return await(createFeedbackAsync(feedback, collectionMethod));
    }

    private void logFeedbackInfo(Map<String, Object> fields) {
        if (silent) {
            return;
        }
        StringBuilder out = new StringBuilder("\n📝 CREATING FEEDBACK for LLM Request Log ID: ")
                .append(fields.get("llm_request_log_id"))
                .append("\n🎭 Sentiment: ").append(fields.getOrDefault("sentiment", "N/A"));
        Object explanation = fields.get("explanation");
        if (explanation instanceof String text && !text.isEmpty()) {
            out.append("\n💭 Explanation: ").append(text.length() > 100 ? text.substring(0, 100) + "..." : text);
        }
        out.append(dryRun ? "\n🚫 DRY RUN: API call will be skipped" : "\n📤 Sending to: " + apiEndpoint);
        log.info(out.toString());
    }

    /**
     * Searches feedback with raw Ransack predicates (private API key). Returns the {@code :summary}
     * view of each record plus the body's pagination envelope.
     */
    public CompletableFuture<Page<JsonNode>> searchFeedbackAsync(SearchFeedbackParams params) {
        SearchFeedbackParams p = params == null ? SearchFeedbackParams.NONE : params;
        Query q = new Query();
        p.predicates().forEach((key, value) -> q.set("q[" + key + "]", value));
        q.set("page", p.page());
        q.set("per", p.per());
        return getJson(q.appendTo(apiEndpoint), "Feedback").thenApply(body -> {
            JsonNode feedback = body.get("feedback");
            List<JsonNode> items = feedback == null || feedback.isNull() ? List.of() : Services.items(feedback, "Feedback");
            return new Page<>(items, Pagination.fromJson(body.get("pagination")));
        });
    }

    public Page<JsonNode> searchFeedback(SearchFeedbackParams params) {
        return await(searchFeedbackAsync(params));
    }

    /** One feedback record with {@code original_output}, {@code revised_output} and {@code feedback_partials}. */
    public CompletableFuture<JsonNode> getFeedbackAsync(String id) {
        return getJson(buildResourceUrl(id, "getFeedback: id must be a non-empty string"), "Feedback");
    }

    public JsonNode getFeedback(String id) {
        return await(getFeedbackAsync(id));
    }
}
