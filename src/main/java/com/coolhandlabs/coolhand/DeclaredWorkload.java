package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A workload named by an existing hashid ({@code id}) or a stable {@code key} that is
 * find-or-created. {@code name}/{@code description} apply only when the server creates the record;
 * ingest never renames. Sent inside {@code llm_request_log.declared.workload}.
 *
 * @param id existing workload hashid (wins over {@code key} server-side)
 * @param key stable key: at most 255 characters, no control or invisible characters, no leading or
 *     trailing whitespace, no leading/trailing or doubled {@code /}
 * @param name display name used on creation
 * @param description at most 1000 characters, used on creation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "key", "name", "description"})
public record DeclaredWorkload(String id, String key, String name, String description) {

    public static DeclaredWorkload ofKey(String key) {
        return new DeclaredWorkload(null, key, null, null);
    }

    public static DeclaredWorkload ofId(String id) {
        return new DeclaredWorkload(id, null, null, null);
    }

    public DeclaredWorkload withName(String name) {
        return new DeclaredWorkload(id, key, name, description);
    }

    public DeclaredWorkload withDescription(String description) {
        return new DeclaredWorkload(id, key, name, description);
    }
}
