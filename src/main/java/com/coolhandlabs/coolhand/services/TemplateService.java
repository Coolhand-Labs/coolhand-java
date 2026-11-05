package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.internal.EntityKeys;
import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.model.MetricsWindow;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchTemplatesParams;
import com.coolhandlabs.coolhand.model.UpsertResult;
import com.coolhandlabs.coolhand.model.UpsertTemplateParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** LLM request templates: list/search, get one, and upsert by key ({@code /api/v2/llm_request_templates}). */
public final class TemplateService extends BaseService {
    public TemplateService(ServiceConfig config) {
        super(config, "/api/v2/llm_request_templates");
    }

    static Query metrics(Query q, MetricsWindow m) {
        MetricsWindow w = m == null ? MetricsWindow.NONE : m;
        return q.set("include_metrics", w.includeMetrics())
                .set("days_back", w.daysBack())
                .set("since", toTimestampParam(w.since()))
                .set("until", toTimestampParam(w.until()));
    }

    public CompletableFuture<Page<JsonNode>> searchTemplatesAsync(SearchTemplatesParams params) {
        SearchTemplatesParams p = params == null ? SearchTemplatesParams.NONE : params;
        Query q = new Query()
                .set("search", p.search())
                .set("workload_id", p.workloadId())
                .set("status", p.status())
                .set("include_deprecated", p.includeDeprecated())
                .set("include_system", p.includeSystem());
        metrics(q, p.metrics()).set("page", p.page()).set("per", p.per());
        return listPage(q.appendTo(apiEndpoint), "Template", p.page(), p.per());
    }

    public Page<JsonNode> searchTemplates(SearchTemplatesParams params) {
        return await(searchTemplatesAsync(params));
    }

    /** One template by hashid, with its prompt patterns; metrics included unless {@code includeMetrics} is false. */
    public CompletableFuture<JsonNode> getTemplateAsync(String id, MetricsWindow opts) {
        String url = buildResourceUrl(id, "getTemplate: id must be a non-empty string");
        return getJson(metrics(new Query(), opts).appendTo(url), "Template");
    }

    public JsonNode getTemplate(String id, MetricsWindow opts) {
        return await(getTemplateAsync(id, opts));
    }

    /** Creates or updates a template by key (private key); empty on a dry run. */
    public CompletableFuture<Optional<UpsertResult>> upsertAsync(UpsertTemplateParams params) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (params.name() != null) {
            body.put("name", params.name());
        }
        if (params.reporter() != null) {
            body.put("reporter", params.reporter());
        }
        if (params.workloadId() != null || params.workloadKey() != null) {
            if (params.workloadId() != null && !JsText.isBlank(params.workloadId())) {
                body.put("workload_id", params.workloadId());
            } else if (params.workloadKey() != null && !JsText.isBlank(params.workloadKey())) {
                String problem = EntityKeys.keyProblem(params.workloadKey());
                if (problem != null) {
                    throw new IllegalArgumentException("Template upsert: workload key " + problem + " (got: \""
                            + params.workloadKey() + "\")");
                }
                body.put("workload_key", params.workloadKey());
            } else {
                throw new IllegalArgumentException(
                        "Template upsert: workload must be { id } or { key } with a non-blank string");
            }
        }
        return putByKey(params.key(), body, "Template")
                .thenApply(r -> r.map(u -> new UpsertResult(u.record(), u.created())));
    }

    public Optional<UpsertResult> upsert(UpsertTemplateParams params) {
        return await(upsertAsync(params));
    }
}
