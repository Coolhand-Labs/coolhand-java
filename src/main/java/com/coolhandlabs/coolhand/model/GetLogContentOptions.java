package com.coolhandlabs.coolhand.model;

import java.util.Locale;

/**
 * Options for {@code getLogContent}. {@code searchQuery} (snippet search) is mutually exclusive with
 * {@code section}/{@code maxChars} (slicing a large log); {@link Builder#build()} enforces that.
 *
 * @param section which part of each field to return; only takes effect with {@code maxChars}
 * @param maxChars max characters per content field
 * @param searchQuery text to search for; returns up to 5 matching snippets per field
 * @param includeThinking include {@code thinking_response}
 * @param includeTurnMetrics include {@code turn_metrics} (captured agent sessions only)
 */
public record GetLogContentOptions(Section section, Integer maxChars, String searchQuery, Boolean includeThinking,
        Boolean includeTurnMetrics) {

    public static final GetLogContentOptions NONE = new GetLogContentOptions(null, null, null, null, null);

    /** Part of each content field to return. */
    public enum Section {
        FULL, BEGINNING, END;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public GetLogContentOptions {
        if (searchQuery != null && (section != null || maxChars != null)) {
            throw new IllegalArgumentException("getLogContent: searchQuery cannot be combined with section/maxChars");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link GetLogContentOptions}. */
    public static final class Builder {
        private Section section;
        private Integer maxChars;
        private String searchQuery;
        private Boolean includeThinking;
        private Boolean includeTurnMetrics;

        private Builder() {}

        public Builder section(Section section) { this.section = section; return this; }
        public Builder maxChars(int maxChars) { this.maxChars = maxChars; return this; }
        public Builder searchQuery(String searchQuery) { this.searchQuery = searchQuery; return this; }
        public Builder includeThinking(boolean value) { this.includeThinking = value; return this; }
        public Builder includeTurnMetrics(boolean value) { this.includeTurnMetrics = value; return this; }

        public GetLogContentOptions build() {
            return new GetLogContentOptions(section, maxChars, searchQuery, includeThinking, includeTurnMetrics);
        }
    }
}
