package com.coolhandlabs.coolhand.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A workload or template upserted by key.
 *
 * @param record the record as the server returned it ({@code id}, {@code name}, ...)
 * @param created true when the server created it (201), false when it updated it (200)
 */
public record UpsertResult(JsonNode record, boolean created) {}
