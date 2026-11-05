package com.coolhandlabs.coolhand.internal;

import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.DeclaredTemplate;
import com.coolhandlabs.coolhand.DeclaredWorkload;
import java.util.Optional;

/**
 * Thread-scoped declared context (coolhand-node's {@code declared-context.ts}, with a
 * {@link ThreadLocal} in place of {@code AsyncLocalStorage}): validation, nesting and the current value.
 */
public final class DeclaredContexts {
    private static final ThreadLocal<DeclaredContext> CURRENT = new ThreadLocal<>();

    private DeclaredContexts() {}

    /** The raw (possibly empty) context installed on this thread, or {@code null}. */
    public static DeclaredContext installed() {
        return CURRENT.get();
    }

    /** The declaration active on this thread; empty when none, or when the active one declares nothing. */
    public static Optional<DeclaredContext> current() {
        DeclaredContext ctx = CURRENT.get();
        return ctx == null || ctx.isEmpty() ? Optional.empty() : Optional.of(ctx);
    }

    /** Installs {@code ctx} (already merged) and returns what it replaced, for {@link #restore}. */
    public static DeclaredContext install(DeclaredContext ctx) {
        DeclaredContext previous = CURRENT.get();
        CURRENT.set(ctx);
        return previous;
    }

    public static void restore(DeclaredContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static String nonBlank(String value) {
        return value != null && !JsText.isBlank(value) ? value : null;
    }

    private static void validateKey(String field, String key) {
        String problem = EntityKeys.keyProblem(key);
        if (problem != null) {
            throw new IllegalArgumentException("withContext: " + field + ".key " + problem);
        }
    }

    private static DeclaredWorkload normalizeWorkload(DeclaredWorkload w) {
        String id = nonBlank(w.id());
        if (w.key() != null) {
            validateKey("workload", w.key());
        }
        if (id == null && w.key() == null) {
            throw new IllegalArgumentException("withContext: workload needs a non-blank \"id\" or a \"key\"");
        }
        if (w.description() != null) {
            String problem = EntityKeys.descriptionProblem(w.description());
            if (problem != null) {
                throw new IllegalArgumentException("withContext: workload.description " + problem);
            }
        }
        return new DeclaredWorkload(id, w.key(), w.name(), w.description());
    }

    private static DeclaredTemplate normalizeTemplate(DeclaredTemplate t) {
        String id = nonBlank(t.id());
        if (t.key() != null) {
            validateKey("template", t.key());
        }
        if (id == null && t.key() == null) {
            throw new IllegalArgumentException("withContext: template needs a non-blank \"id\" or a \"key\"");
        }
        return new DeclaredTemplate(id, t.key(), t.name());
    }

    /**
     * Validates {@code ctx} (throwing before anything runs) and merges it over {@code parent}: per
     * field the innermost value wins, workload/template are replaced wholesale, and a context that
     * sets a workload without a template does not inherit the outer template.
     *
     * @throws IllegalArgumentException for a malformed declaration
     */
    public static DeclaredContext merge(DeclaredContext parent, DeclaredContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException(
                    "withContext: first argument must be a DeclaredContext ({ reporter, workload, template })");
        }
        String reporter = nonBlank(ctx.reporter());
        DeclaredWorkload workload = ctx.workload() == null ? null : normalizeWorkload(ctx.workload());
        DeclaredTemplate template = ctx.template() == null ? null : normalizeTemplate(ctx.template());

        String mergedReporter = reporter != null ? reporter : parent == null ? null : parent.reporter();
        DeclaredWorkload mergedWorkload = workload != null ? workload : parent == null ? null : parent.workload();
        DeclaredTemplate mergedTemplate = template != null ? template
                : workload != null || parent == null ? null : parent.template();

        // A template key is find-or-created under a workload; with none, the server 422s and drops the log.
        if (mergedTemplate != null && mergedTemplate.key() != null && mergedTemplate.id() == null
                && mergedWorkload == null) {
            throw new IllegalArgumentException(
                    "withContext: template.key needs a workload in this or an enclosing withContext (or use template.id)");
        }
        return new DeclaredContext(mergedReporter, mergedWorkload, mergedTemplate);
    }
}
