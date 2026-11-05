package com.coolhandlabs.coolhand.model;

import java.time.Instant;

/**
 * Named filters for {@code searchLogs} (not raw Ransack predicates). Every field is optional.
 * See {@code docs/log-search.md}.
 *
 * @param templateId template hashid
 * @param workloadId workload hashid
 * @param systemPromptContains substring of the system prompt
 * @param userPromptContains substring of the user prompt
 * @param model model name
 * @param sourceApi detected source API
 * @param sourceApiResult detected source API result
 * @param sourceApplication source application
 * @param projectPath the {@code project_path} metadata value
 * @param unmatchedOnly only logs no template matched
 * @param daysBack rolling window in days
 * @param since window start ({@code Instant} or ISO-8601 {@code String})
 * @param until window end ({@code Instant} or ISO-8601 {@code String})
 * @param minCost minimum cost in USD (priceable logs only)
 * @param order {@code "cost_desc"}
 * @param includePrompts include {@code system_prompt}/{@code user_prompt}
 * @param sort Ransack sort expression, sent as {@code q[s]}
 * @param page 1-based page
 * @param per page size (server default 25, max 100)
 * @param includeTotal ask for exact totals (a COUNT on the server)
 */
public record SearchLogsParams(String templateId, String workloadId, String systemPromptContains,
        String userPromptContains, String model, String sourceApi, String sourceApiResult, String sourceApplication,
        String projectPath, Boolean unmatchedOnly, Integer daysBack, Object since, Object until, Number minCost,
        String order, Boolean includePrompts, String sort, Integer page, Integer per, Boolean includeTotal) {

    public static final SearchLogsParams NONE = builder().build();

    public SearchLogsParams {
        MetricsWindow.checkBound(since, "since");
        MetricsWindow.checkBound(until, "until");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SearchLogsParams}. */
    public static final class Builder {
        private String templateId;
        private String workloadId;
        private String systemPromptContains;
        private String userPromptContains;
        private String model;
        private String sourceApi;
        private String sourceApiResult;
        private String sourceApplication;
        private String projectPath;
        private Boolean unmatchedOnly;
        private Integer daysBack;
        private Object since;
        private Object until;
        private Number minCost;
        private String order;
        private Boolean includePrompts;
        private String sort;
        private Integer page;
        private Integer per;
        private Boolean includeTotal;

        private Builder() {}

        public Builder templateId(String v) { this.templateId = v; return this; }
        public Builder workloadId(String v) { this.workloadId = v; return this; }
        public Builder systemPromptContains(String v) { this.systemPromptContains = v; return this; }
        public Builder userPromptContains(String v) { this.userPromptContains = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder sourceApi(String v) { this.sourceApi = v; return this; }
        public Builder sourceApiResult(String v) { this.sourceApiResult = v; return this; }
        public Builder sourceApplication(String v) { this.sourceApplication = v; return this; }
        public Builder projectPath(String v) { this.projectPath = v; return this; }
        public Builder unmatchedOnly(boolean v) { this.unmatchedOnly = v; return this; }
        public Builder daysBack(int v) { this.daysBack = v; return this; }
        public Builder since(Instant v) { this.since = v; return this; }
        public Builder since(String v) { this.since = v; return this; }
        public Builder until(Instant v) { this.until = v; return this; }
        public Builder until(String v) { this.until = v; return this; }
        public Builder minCost(Number v) { this.minCost = v; return this; }
        public Builder order(String v) { this.order = v; return this; }
        public Builder includePrompts(boolean v) { this.includePrompts = v; return this; }
        public Builder sort(String v) { this.sort = v; return this; }
        public Builder page(int v) { this.page = v; return this; }
        public Builder per(int v) { this.per = v; return this; }
        public Builder includeTotal(boolean v) { this.includeTotal = v; return this; }

        public SearchLogsParams build() {
            return new SearchLogsParams(templateId, workloadId, systemPromptContains, userPromptContains, model,
                    sourceApi, sourceApiResult, sourceApplication, projectPath, unmatchedOnly, daysBack, since, until,
                    minCost, order, includePrompts, sort, page, per, includeTotal);
        }
    }
}
