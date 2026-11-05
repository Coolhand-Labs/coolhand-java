package com.coolhandlabs.coolhand;

import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The feedback record the server created. Ids are hashids. Absent fields are {@code null};
 * {@link #raw()} holds the full response for anything not modelled here.
 *
 * @param id feedback hashid
 * @param llmRequestLogId hashid of the matched log, or {@code null} when it matched none
 * @param sentiment like, dislike or neutral
 * @param creatorType human, agent or unknown
 * @param creatorUniqueId as sent
 * @param workloadId hashid of the associated workload
 * @param explanation as sent
 * @param revisedOutput as sent
 * @param llmProviderUniqueId as sent
 * @param originalOutput as sent
 * @param clientUniqueId as sent
 * @param clientId hashid of the owning client
 * @param collector the collector string recorded
 * @param coolhandFingerprintId fingerprint id
 * @param warnings validation issues reported while creating the record
 * @param createdPartialId hashid of a partial created with sectional highlighting
 * @param createdAt creation time
 * @param updatedAt update time
 * @param raw the full JSON response
 */
public record FeedbackResponse(
        String id,
        String llmRequestLogId,
        Sentiment sentiment,
        CreatorType creatorType,
        String creatorUniqueId,
        String workloadId,
        String explanation,
        String revisedOutput,
        String llmProviderUniqueId,
        String originalOutput,
        String clientUniqueId,
        String clientId,
        String collector,
        String coolhandFingerprintId,
        List<String> warnings,
        String createdPartialId,
        String createdAt,
        String updatedAt,
        JsonNode raw) {

    /** Reads a feedback record from its JSON. */
    public static FeedbackResponse fromJson(JsonNode node) {
        List<String> warnings = new ArrayList<>();
        JsonNode w = node.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode item : w) {
                warnings.add(item.isObject() && item.has("message") ? item.get("message").asText() : item.asText());
            }
        }
        return new FeedbackResponse(
                Json.text(node, "id"),
                Json.text(node, "llm_request_log_id"),
                Sentiment.fromWire(Json.text(node, "sentiment")),
                CreatorType.fromWire(Json.text(node, "creator_type")),
                Json.text(node, "creator_unique_id"),
                Json.text(node, "workload_id"),
                Json.text(node, "explanation"),
                Json.text(node, "revised_output"),
                Json.text(node, "llm_provider_unique_id"),
                Json.text(node, "original_output"),
                Json.text(node, "client_unique_id"),
                Json.text(node, "client_id"),
                Json.text(node, "collector"),
                Json.text(node, "coolhand_fingerprint_id"),
                List.copyOf(warnings),
                Json.text(node, "created_partial_id"),
                Json.text(node, "created_at"),
                Json.text(node, "updated_at"),
                node);
    }
}
