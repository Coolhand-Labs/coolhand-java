package com.coolhandlabs.coolhand.model;

import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A feedback linked to an optimization as evidence.
 *
 * @param id the link's hashid (used to unlink)
 * @param optimizationId optimization hashid
 * @param feedbackId feedback hashid
 * @param note note, or {@code null}
 * @param createdAt creation time
 */
public record OptimizationFeedbackLink(String id, String optimizationId, String feedbackId, String note,
        String createdAt) {

    public static OptimizationFeedbackLink fromJson(JsonNode node) {
        return new OptimizationFeedbackLink(Json.text(node, "id"), Json.text(node, "optimization_id"),
                Json.text(node, "feedback_id"), Json.text(node, "note"), Json.text(node, "created_at"));
    }
}
