package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The identity passed to {@link Coolhand#withContext(DeclaredContext, ContextCall)} and sent as
 * {@code llm_request_log.declared}: which workload and template the LLM calls made inside the block
 * belong to, so the server skips log-to-template matching. Every field is optional.
 *
 * @param reporter who is declaring (applies only when a record is first created); blank means none
 * @param workload the workload, or {@code null} to inherit the enclosing one
 * @param template the template, or {@code null} to inherit the enclosing one (unless this context
 *     sets a workload, which resets the template)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"reporter", "workload", "template"})
public record DeclaredContext(String reporter, DeclaredWorkload workload, DeclaredTemplate template) {

    public static Builder builder() {
        return new Builder();
    }

    /** True when nothing is declared (such a context is never sent). */
    @JsonIgnore
    public boolean isEmpty() {
        return reporter == null && workload == null && template == null;
    }

    /** Builder for {@link DeclaredContext}. */
    public static final class Builder {
        private String reporter;
        private DeclaredWorkload workload;
        private DeclaredTemplate template;

        private Builder() {}

        public Builder reporter(String reporter) {
            this.reporter = reporter;
            return this;
        }

        public Builder workload(DeclaredWorkload workload) {
            this.workload = workload;
            return this;
        }

        /** Shorthand for a workload by key with a name and description. */
        public Builder workload(String key, String name, String description) {
            return workload(new DeclaredWorkload(null, key, name, description));
        }

        public Builder template(DeclaredTemplate template) {
            this.template = template;
            return this;
        }

        /** Shorthand for a template by key with a name. */
        public Builder template(String key, String name) {
            return template(new DeclaredTemplate(null, key, name));
        }

        public DeclaredContext build() {
            return new DeclaredContext(reporter, workload, template);
        }
    }
}
