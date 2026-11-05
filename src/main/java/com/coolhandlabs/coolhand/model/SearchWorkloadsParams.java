package com.coolhandlabs.coolhand.model;

/**
 * Filters for {@code searchWorkloads}. See {@code docs/workload-search.md}.
 *
 * @param search name search
 * @param includeArchived include archived workloads
 * @param includeSystem include system workloads
 * @param includeTemplates include each workload's templates
 * @param metrics metrics options
 * @param page 1-based page
 * @param per page size
 */
public record SearchWorkloadsParams(String search, Boolean includeArchived, Boolean includeSystem,
        Boolean includeTemplates, MetricsWindow metrics, Integer page, Integer per) {
    public static final SearchWorkloadsParams NONE = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SearchWorkloadsParams}. */
    public static final class Builder {
        private String search;
        private Boolean includeArchived;
        private Boolean includeSystem;
        private Boolean includeTemplates;
        private MetricsWindow metrics = MetricsWindow.NONE;
        private Integer page;
        private Integer per;

        private Builder() {}

        public Builder search(String v) { this.search = v; return this; }
        public Builder includeArchived(boolean v) { this.includeArchived = v; return this; }
        public Builder includeSystem(boolean v) { this.includeSystem = v; return this; }
        public Builder includeTemplates(boolean v) { this.includeTemplates = v; return this; }
        public Builder metrics(MetricsWindow v) { this.metrics = v; return this; }
        public Builder page(int v) { this.page = v; return this; }
        public Builder per(int v) { this.per = v; return this; }

        public SearchWorkloadsParams build() {
            return new SearchWorkloadsParams(search, includeArchived, includeSystem, includeTemplates, metrics, page, per);
        }
    }
}
