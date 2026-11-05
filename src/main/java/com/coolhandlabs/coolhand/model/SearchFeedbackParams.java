package com.coolhandlabs.coolhand.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw Ransack predicates for {@code searchFeedback}, each sent as {@code q[<name>]=<value>}, plus
 * top-level {@code page}/{@code per}. Sentiment is filtered by its integer code
 * ({@code sentiment_eq}: 0 dislike, 1 neutral, 2 like).
 *
 * @param predicates predicate name to value, e.g. {@code explanation_cont}, {@code s} (sort)
 * @param page 1-based page
 * @param per page size
 */
public record SearchFeedbackParams(Map<String, Object> predicates, Integer page, Integer per) {
    public static final SearchFeedbackParams NONE = builder().build();

    public SearchFeedbackParams {
        predicates = Collections.unmodifiableMap(new LinkedHashMap<>(predicates == null ? Map.of() : predicates));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SearchFeedbackParams}. */
    public static final class Builder {
        private final Map<String, Object> predicates = new LinkedHashMap<>();
        private Integer page;
        private Integer per;

        private Builder() {}

        /** Any Ransack predicate the endpoint accepts, e.g. {@code predicate("created_at_gteq", "2026-01-01")}. */
        public Builder predicate(String name, Object value) { predicates.put(name, value); return this; }
        /** {@code q[s]}: Ransack sort, e.g. {@code "created_at desc"}. */
        public Builder sort(String s) { return predicate("s", s); }
        /** {@code q[sentiment_eq]}: 0 dislike, 1 neutral, 2 like. */
        public Builder sentimentEq(int code) { return predicate("sentiment_eq", code); }
        public Builder explanationCont(String text) { return predicate("explanation_cont", text); }
        public Builder page(int page) { this.page = page; return this; }
        public Builder per(int per) { this.per = per; return this; }

        public SearchFeedbackParams build() {
            return new SearchFeedbackParams(predicates, page, per);
        }
    }
}
