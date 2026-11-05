package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pagination of a list result, read from the {@code X-Page}/{@code X-Per-Page}/{@code X-Total-Count}/
 * {@code X-Total-Pages} headers (or the body envelope for feedback search). Without total headers the
 * totals are a lower-bound estimate; see {@code docs/log-search.md}.
 */
public record Pagination(
        int currentPage,
        int perPage,
        long totalCount,
        long totalPages,
        boolean hasNextPage,
        boolean hasPrevPage) {

    /** Reads the {@code pagination} object of a body envelope (snake_case keys). */
    public static Pagination fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Pagination(1, 0, 0, 0, false, false);
        }
        return new Pagination(
                node.path("current_page").asInt(1),
                node.path("per_page").asInt(0),
                node.path("total_count").asLong(0),
                node.path("total_pages").asLong(0),
                node.path("has_next_page").asBoolean(false),
                node.path("has_prev_page").asBoolean(false));
    }
}
