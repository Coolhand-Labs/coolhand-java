package com.coolhandlabs.coolhand.model;

/**
 * Filters for {@code listStepTemplates}. See {@code docs/step-templates.md}.
 *
 * @param search name search
 * @param stepType {@code prompt_step} or {@code tool_step}
 * @param includeMetrics include per-row metrics (caps {@code per} at 25 server-side)
 * @param page 1-based page
 * @param per page size
 */
public record ListStepTemplatesParams(String search, String stepType, Boolean includeMetrics, Integer page,
        Integer per) {
    public static final ListStepTemplatesParams NONE = new ListStepTemplatesParams(null, null, null, null, null);
}
