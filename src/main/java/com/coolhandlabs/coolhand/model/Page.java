package com.coolhandlabs.coolhand.model;

import com.coolhandlabs.coolhand.Pagination;
import java.util.List;

/**
 * One page of a list or search result. Server records are returned as Jackson {@code JsonNode}s
 * (their fields are documented in {@code docs/}), so new server fields are never lost.
 *
 * @param items the records on this page
 * @param pagination page numbers and totals
 * @param <T> the record type
 */
public record Page<T>(List<T> items, Pagination pagination) {
    public Page {
        items = List.copyOf(items);
    }
}
