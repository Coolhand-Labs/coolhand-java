package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.model.ListStepTemplatesParams;
import com.coolhandlabs.coolhand.model.Page;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

/** Step templates: recurring steps detected in captured agent sessions ({@code /api/v2/step_templates}). */
public final class StepTemplateService extends BaseService {
    public StepTemplateService(ServiceConfig config) {
        super(config, "/api/v2/step_templates");
    }

    public CompletableFuture<Page<JsonNode>> listStepTemplatesAsync(ListStepTemplatesParams params) {
        ListStepTemplatesParams p = params == null ? ListStepTemplatesParams.NONE : params;
        Query q = new Query()
                .set("search", p.search())
                .set("step_type", p.stepType())
                .set("include_metrics", p.includeMetrics())
                .set("page", p.page())
                .set("per", p.per());
        return listPage(q.appendTo(apiEndpoint), "Step template", p.page(), p.per());
    }

    public Page<JsonNode> listStepTemplates(ListStepTemplatesParams params) {
        return await(listStepTemplatesAsync(params));
    }

    /**
     * One step template by its integer id; metrics included unless {@code includeMetrics} is false.
     *
     * @throws IllegalArgumentException if {@code id} is not positive
     */
    public CompletableFuture<JsonNode> getStepTemplateAsync(long id, Boolean includeMetrics) {
        if (id <= 0 || id > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("getStepTemplate: id must be a positive integer");
        }
        return getJson(new Query().set("include_metrics", includeMetrics).appendTo(apiEndpoint + "/" + id), "Step template");
    }

    public JsonNode getStepTemplate(long id, Boolean includeMetrics) {
        return await(getStepTemplateAsync(id, includeMetrics));
    }
}
