package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A template named by {@code id} or {@code key} ({@code id} wins). A {@code key} needs a workload in
 * the same or an enclosing {@code withContext}. Sent inside {@code llm_request_log.declared.template}.
 *
 * @param id existing template hashid
 * @param key stable key, same rules as {@link DeclaredWorkload#key()}
 * @param name display name used on creation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "key", "name"})
public record DeclaredTemplate(String id, String key, String name) {

    public static DeclaredTemplate ofKey(String key) {
        return new DeclaredTemplate(null, key, null);
    }

    public static DeclaredTemplate ofId(String id) {
        return new DeclaredTemplate(id, null, null);
    }

    public DeclaredTemplate withName(String name) {
        return new DeclaredTemplate(id, key, name);
    }
}
