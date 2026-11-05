package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.internal.EntityKeys;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchWorkloadsParams;
import com.coolhandlabs.coolhand.model.UpsertResult;
import com.coolhandlabs.coolhand.model.UpsertWorkloadParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Workloads: list with cost/performance metrics, and upsert by key ({@code /api/v2/workloads}). */
public final class WorkloadService extends BaseService {
    public WorkloadService(ServiceConfig config) {
        super(config, "/api/v2/workloads");
    }

    public CompletableFuture<Page<JsonNode>> searchWorkloadsAsync(SearchWorkloadsParams params) {
        SearchWorkloadsParams p = params == null ? SearchWorkloadsParams.NONE : params;
        Query q = new Query()
                .set("search", p.search())
                .set("include_archived", p.includeArchived())
                .set("include_system", p.includeSystem())
                .set("include_templates", p.includeTemplates());
        TemplateService.metrics(q, p.metrics()).set("page", p.page()).set("per", p.per());
        return listPage(q.appendTo(apiEndpoint), "Workload", p.page(), p.per());
    }

    public Page<JsonNode> searchWorkloads(SearchWorkloadsParams params) {
        return await(searchWorkloadsAsync(params));
    }

    /** Creates or updates a workload by key (private key); empty on a dry run. */
    public CompletableFuture<Optional<UpsertResult>> upsertAsync(UpsertWorkloadParams params) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (params.name() != null) {
            body.put("name", params.name());
        }
        if (params.description() != null) {
            String problem = EntityKeys.descriptionProblem(params.description());
            if (problem != null) {
                throw new IllegalArgumentException("Workload upsert: description " + problem);
            }
            body.put("description", params.description());
        }
        if (params.reporter() != null) {
            body.put("reporter", params.reporter());
        }
        return putByKey(params.key(), body, "Workload")
                .thenApply(r -> r.map(u -> new UpsertResult(u.record(), u.created())));
    }

    public Optional<UpsertResult> upsert(UpsertWorkloadParams params) {
        return await(upsertAsync(params));
    }
}
