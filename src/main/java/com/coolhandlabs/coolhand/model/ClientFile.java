package com.coolhandlabs.coolhand.model;

import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A client file record.
 *
 * @param id hashid
 * @param name display name
 * @param fileType file type
 * @param status draft, published or archived
 * @param description description, or {@code null}
 * @param metadata metadata object
 * @param createdAt creation time
 * @param raw the full JSON record
 */
public record ClientFile(String id, String name, String fileType, String status, String description,
        JsonNode metadata, String createdAt, JsonNode raw) {

    public static ClientFile fromJson(JsonNode node) {
        return new ClientFile(Json.text(node, "id"), Json.text(node, "name"), Json.text(node, "file_type"),
                Json.text(node, "status"), Json.text(node, "description"), node.get("metadata"),
                Json.text(node, "created_at"), node);
    }
}
