package com.coolhandlabs.coolhand.model;

import java.time.Instant;

/**
 * The metrics options shared by template and workload reads: whether to include a {@code metrics}
 * object, over a rolling {@code daysBack} window or an explicit {@code since}/{@code until} one
 * (explicit wins). Bounds are an {@link Instant} or an ISO-8601 string passed through as is.
 *
 * @param includeMetrics include per-record metrics
 * @param daysBack rolling window in days
 * @param since window start ({@code Instant} or {@code String})
 * @param until window end ({@code Instant} or {@code String})
 */
public record MetricsWindow(Boolean includeMetrics, Integer daysBack, Object since, Object until) {
    public static final MetricsWindow NONE = new MetricsWindow(null, null, null, null);

    public MetricsWindow {
        checkBound(since, "since");
        checkBound(until, "until");
    }

    static void checkBound(Object bound, String name) {
        if (bound != null && !(bound instanceof Instant) && !(bound instanceof String)) {
            throw new IllegalArgumentException(name + " must be an Instant or an ISO8601 string");
        }
    }

    public static MetricsWindow include(boolean includeMetrics) {
        return new MetricsWindow(includeMetrics, null, null, null);
    }

    public MetricsWindow withDaysBack(int daysBack) {
        return new MetricsWindow(includeMetrics, daysBack, since, until);
    }

    public MetricsWindow between(Instant since, Instant until) {
        return new MetricsWindow(includeMetrics, daysBack, since, until);
    }

    public MetricsWindow between(String since, String until) {
        return new MetricsWindow(includeMetrics, daysBack, since, until);
    }
}
