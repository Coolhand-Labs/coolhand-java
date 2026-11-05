package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.CoolhandException;
import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.internal.Json;
import com.coolhandlabs.coolhand.model.BulkLinkFeedbackResult;
import com.coolhandlabs.coolhand.model.OptimizationFeedbackLink;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Links feedback to optimizations as evidence ({@code /api/v2/optimizations/:id/feedback_links}). */
public final class OptimizationFeedbackLinkService extends BaseService {
    static final int BULK_LINK_BATCH_SIZE = 100;
    private static final Object NO_NOTE = new Object();

    public OptimizationFeedbackLinkService(ServiceConfig config) {
        super(config, "/api/v2/optimizations");
    }

    private static void assertId(String id, String message) {
        if (id == null || JsText.trim(id).isEmpty() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException(message);
        }
    }

    private String linksUrl(String optimizationId, String method) {
        assertId(optimizationId, method + ": optimizationId must be a non-empty string");
        return apiEndpoint + "/" + JsText.encodeURIComponent(optimizationId) + "/feedback_links";
    }

    private boolean skipForDryRun(String method, String optimizationId) {
        if (!dryRun) {
            return false;
        }
        log.info("🚫 DRY RUN: Skipping " + method + " for optimization " + optimizationId);
        return true;
    }

    private CompletableFuture<JsonNode> postLinks(String url, Map<String, Object> payload) {
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload), StandardCharsets.UTF_8))
                .build();
        return fetchOrThrow(request, "Feedback link request failed").thenApply(text -> parseJsonObject(text, "Feedback link"));
    }

    private static void putNote(Map<String, Object> payload, Object note) {
        if (note != NO_NOTE) {
            payload.put("note", note);
        }
    }

    /** Links one feedback (no note). Empty on a dry run. */
    public CompletableFuture<Optional<OptimizationFeedbackLink>> linkFeedbackAsync(String optimizationId, String feedbackId) {
        return link(optimizationId, feedbackId, NO_NOTE);
    }

    /** Links one feedback with {@code note} (a {@code null} note is sent as JSON null). Empty on a dry run. */
    public CompletableFuture<Optional<OptimizationFeedbackLink>> linkFeedbackAsync(String optimizationId, String feedbackId,
            String note) {
        return link(optimizationId, feedbackId, note);
    }

    private CompletableFuture<Optional<OptimizationFeedbackLink>> link(String optimizationId, String feedbackId, Object note) {
        assertId(feedbackId, "linkFeedback: feedbackId must be a non-empty string");
        String url = linksUrl(optimizationId, "linkFeedback");
        if (skipForDryRun("linkFeedback", optimizationId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feedback_id", feedbackId);
        putNote(payload, note);
        return postLinks(url, payload).thenApply(n -> Optional.of(OptimizationFeedbackLink.fromJson(n)));
    }

    public Optional<OptimizationFeedbackLink> linkFeedback(String optimizationId, String feedbackId) {
        return await(linkFeedbackAsync(optimizationId, feedbackId));
    }

    public Optional<OptimizationFeedbackLink> linkFeedback(String optimizationId, String feedbackId, String note) {
        return await(linkFeedbackAsync(optimizationId, feedbackId, note));
    }

    /** Links many feedbacks in batches of 100, merging the counts. Empty on a dry run. */
    public CompletableFuture<Optional<BulkLinkFeedbackResult>> bulkLinkFeedbackAsync(String optimizationId,
            List<String> feedbackIds) {
        return bulk(optimizationId, feedbackIds, NO_NOTE);
    }

    public CompletableFuture<Optional<BulkLinkFeedbackResult>> bulkLinkFeedbackAsync(String optimizationId,
            List<String> feedbackIds, String note) {
        return bulk(optimizationId, feedbackIds, note);
    }

    private CompletableFuture<Optional<BulkLinkFeedbackResult>> bulk(String optimizationId, List<String> feedbackIds,
            Object note) {
        if (feedbackIds == null || feedbackIds.isEmpty()) {
            throw new IllegalArgumentException("bulkLinkFeedback: feedbackIds must be a non-empty array");
        }
        feedbackIds.forEach(id -> assertId(id, "bulkLinkFeedback: every feedback id must be a non-empty string"));
        String url = linksUrl(optimizationId, "bulkLinkFeedback");
        if (skipForDryRun("bulkLinkFeedback", optimizationId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<long[]> totals = CompletableFuture.completedFuture(new long[3]);
        List<String> notFound = new ArrayList<>();
        for (int i = 0; i < feedbackIds.size(); i += BULK_LINK_BATCH_SIZE) {
            List<String> batch = List.copyOf(feedbackIds.subList(i, Math.min(i + BULK_LINK_BATCH_SIZE, feedbackIds.size())));
            totals = totals.thenCompose(acc -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("feedback_ids", batch);
                putNote(payload, note);
                return postLinks(url, payload).thenApply(result -> {
                    JsonNode linked = result.get("linked");
                    JsonNode already = result.get("already_linked");
                    JsonNode errored = result.get("errored");
                    JsonNode missing = result.get("not_found");
                    if (linked == null || !linked.isNumber() || already == null || !already.isNumber()
                            || errored == null || !errored.isNumber() || missing == null || !missing.isArray()) {
                        throw new CoolhandException("Feedback link response was not a valid bulk result: "
                                + excerpt(result.toString()));
                    }
                    acc[0] += linked.asLong();
                    acc[1] += already.asLong();
                    acc[2] += errored.asLong();
                    missing.forEach(n -> notFound.add(n.asText()));
                    return acc;
                });
            });
        }
        return totals.thenApply(acc -> Optional.of(new BulkLinkFeedbackResult(acc[0], acc[1], acc[2], notFound)));
    }

    public Optional<BulkLinkFeedbackResult> bulkLinkFeedback(String optimizationId, List<String> feedbackIds) {
        return await(bulkLinkFeedbackAsync(optimizationId, feedbackIds));
    }

    public Optional<BulkLinkFeedbackResult> bulkLinkFeedback(String optimizationId, List<String> feedbackIds, String note) {
        return await(bulkLinkFeedbackAsync(optimizationId, feedbackIds, note));
    }

    /** Removes a link by the link's hashid (not the feedback's). No-op on a dry run. */
    public CompletableFuture<Void> unlinkFeedbackAsync(String optimizationId, String linkId) {
        assertId(linkId, "unlinkFeedback: linkId must be a non-empty string");
        String url = linksUrl(optimizationId, "unlinkFeedback") + "/" + JsText.encodeURIComponent(linkId);
        if (skipForDryRun("unlinkFeedback", optimizationId)) {
            return CompletableFuture.completedFuture(null);
        }
        return fetchOrThrow(request(url).header("Accept", "application/json").DELETE().build(),
                "Feedback link request failed").thenApply(text -> null);
    }

    public void unlinkFeedback(String optimizationId, String linkId) {
        await(unlinkFeedbackAsync(optimizationId, linkId));
    }
}
