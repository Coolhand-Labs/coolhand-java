package com.coolhandlabs.coolhand.model;

/**
 * Filters for {@code searchTemplates}. See {@code docs/template-search.md}.
 *
 * @param search name search
 * @param workloadId workload hashid
 * @param status {@code draft}, {@code published} or {@code failure}
 * @param includeDeprecated include deprecated templates
 * @param includeSystem include the Unmatched/Ignored system buckets
 * @param metrics metrics options
 * @param page 1-based page
 * @param per page size
 */
public record SearchTemplatesParams(String search, String workloadId, String status, Boolean includeDeprecated,
        Boolean includeSystem, MetricsWindow metrics, Integer page, Integer per) {
    public static final SearchTemplatesParams NONE = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SearchTemplatesParams}. */
    public static final class Builder {
        private String search;
        private String workloadId;
        private String status;
        private Boolean includeDeprecated;
        private Boolean includeSystem;
        private MetricsWindow metrics = MetricsWindow.NONE;
        private Integer page;
        private Integer per;

        private Builder() {}

        public Builder search(String v) { this.search = v; return this; }
        public Builder workloadId(String v) { this.workloadId = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder includeDeprecated(boolean v) { this.includeDeprecated = v; return this; }
        public Builder includeSystem(boolean v) { this.includeSystem = v; return this; }
        public Builder metrics(MetricsWindow v) { this.metrics = v; return this; }
        public Builder page(int v) { this.page = v; return this; }
        public Builder per(int v) { this.per = v; return this; }

        public SearchTemplatesParams build() {
            return new SearchTemplatesParams(search, workloadId, status, includeDeprecated, includeSystem, metrics, page, per);
        }
    }
}
