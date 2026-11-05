package com.coolhandlabs.coolhand;

import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The log record the server created for a submission.
 *
 * @param id the log id (a raw integer today, a hashid later), as text
 * @param sourceApi detected source API
 * @param sourceApiResult detected source API result
 * @param llmProviderUniqueId the provider's id for the response, for matching feedback
 * @param warnings server warnings: the log was saved, but not exactly as declared
 * @param metadata metadata as stored
 * @param raw the full JSON response
 */
public record LogResponse(
        String id,
        String sourceApi,
        String sourceApiResult,
        String llmProviderUniqueId,
        List<String> warnings,
        JsonNode metadata,
        JsonNode raw) {

    public static LogResponse fromJson(JsonNode node) {
        List<String> warnings = new ArrayList<>();
        JsonNode w = node.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode item : w) {
                warnings.add(item.isObject() && item.has("message") ? item.get("message").asText() : item.asText());
            }
        }
        JsonNode metadata = node.get("metadata");
        return new LogResponse(
                Json.text(node, "id"),
                Json.text(node, "source_api"),
                Json.text(node, "source_api_result"),
                Json.text(node, "llm_provider_unique_id"),
                List.copyOf(warnings),
                metadata == null || metadata.isNull() ? null : metadata,
                node);
    }
}
