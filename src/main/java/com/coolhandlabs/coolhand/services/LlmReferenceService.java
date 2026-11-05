package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.model.ListReferencedFileSessionsParams;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchReferencedFilesParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

/** Files referenced in captured sessions ({@code /api/v2/llm_references}), last 90 days. */
public final class LlmReferenceService extends BaseService {
    public LlmReferenceService(ServiceConfig config) {
        super(config, "/api/v2/llm_references");
    }

    public CompletableFuture<Page<JsonNode>> searchReferencedFilesAsync(SearchReferencedFilesParams params) {
        SearchReferencedFilesParams p = params == null ? SearchReferencedFilesParams.NONE : params;
        Query q = new Query()
                .set("q[file_path_cont]", p.filePathContains())
                .set("q[created_at_gteq]", p.createdAtGteq())
                .set("q[created_at_lteq]", p.createdAtLteq())
                .set("page", p.page())
                .set("per", p.per());
        return listPage(q.appendTo(apiEndpoint), "Referenced file", p.page(), p.per());
    }

    public Page<JsonNode> searchReferencedFiles(SearchReferencedFilesParams params) {
        return await(searchReferencedFilesAsync(params));
    }

    /** Raw rows for one exact file path, newest first. */
    public CompletableFuture<Page<JsonNode>> listReferencedFileSessionsAsync(ListReferencedFileSessionsParams params) {
        if (params == null || params.filePath() == null) {
            throw new IllegalArgumentException("listReferencedFileSessions: filePath must be a string");
        }
        Query q = new Query().set("file_path", params.filePath()).set("page", params.page()).set("per", params.per());
        return listPage(q.appendTo(apiEndpoint + "/sessions"), "Referenced file session", params.page(), params.per());
    }

    public Page<JsonNode> listReferencedFileSessions(ListReferencedFileSessionsParams params) {
        return await(listReferencedFileSessionsAsync(params));
    }
}
