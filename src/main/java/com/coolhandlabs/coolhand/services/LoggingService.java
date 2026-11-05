package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.CallData;
import com.coolhandlabs.coolhand.CollectionMethod;
import com.coolhandlabs.coolhand.DeclaredContext;
import com.coolhandlabs.coolhand.LogResponse;
import com.coolhandlabs.coolhand.MatchedPattern;
import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.internal.Log;
import com.coolhandlabs.coolhand.model.GetLogContentOptions;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchLogsParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Submits captured LLM calls ({@code POST /api/v2/llm_request_logs}) and reads logs back. */
public final class LoggingService extends BaseService {
    public LoggingService(ServiceConfig config) {
        super(config, "/api/v2/llm_request_logs");
    }

    /**
     * Submits one log. The payload is
     * {@code {"llm_request_log": {"raw_request": ..., "collector": ..., "metadata"?: ..., "declared"?: ...}}}.
     * Never throws: resolves to empty on any failure, on a dry run, or when nothing came back.
     *
     * @param collector overrides the SDK collector string when non-null
     * @param metadata free-form object, omitted when null (convention: {@code project_path})
     * @param declared the declared context to send as {@code declared}, or null for none
     */
    public CompletableFuture<Optional<LogResponse>> logRequestToApi(CallData callData, MatchedPattern matchedPattern,
            CollectionMethod collectionMethod, String collector, Map<String, ?> metadata, DeclaredContext declared) {
        try {
            Map<String, Object> logData = new LinkedHashMap<>();
            logData.put("raw_request", callData);
            logData.put("collector", collector != null ? collector : CollectionMethod.collectorString(collectionMethod));
            if (metadata != null) {
                logData.put("metadata", metadata);
            }
            if (declared != null && !declared.isEmpty()) {
                logData.put("declared", declared);
            }
            Map<String, Object> payload = Map.of("llm_request_log", logData);
            logRequestInfo(callData, matchedPattern);
            return sendRequestAsync(payload, "✅ Successfully logged to API with ID for call #" + callData.id())
                    .thenApply(result -> {
                        Optional<LogResponse> response = result.filter(JsonNode::isObject).map(LogResponse::fromJson);
                        response.ifPresent(r -> logWarnings(r.warnings()));
                        logSeparator();
                        return response;
                    })
                    .exceptionally(error -> {
                        log.warnUnlessSilent("❌ Failed to log request to API: " + describeError(unwrap(error)));
                        return Optional.empty();
                    });
        } catch (RuntimeException e) {
            log.warnUnlessSilent("❌ Failed to log request to API: " + describeError(e));
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    // Server warnings mean the log was saved, but not as declared: surface them, never throw.
    private void logWarnings(List<String> warnings) {
        if (silent) {
            return;
        }
        for (String warning : warnings) {
            Log.warn("[coolhand-java] ⚠️ Coolhand warning: " + warning);
        }
    }

    private void logRequestInfo(CallData callData, MatchedPattern matchedPattern) {
        if (silent) {
            return;
        }
        String apiName = matchedPattern != null && matchedPattern.pattern() != null && matchedPattern.pattern().name() != null
                ? matchedPattern.pattern().name() : "API";
        StringBuilder out = new StringBuilder();
        out.append("\n🎉 LOGGING ").append(apiName).append(" API Call #").append(callData.id())
                .append("\n🕐 Time: ").append(callData.timestamp())
                .append("\n🎯 ").append(callData.method()).append(' ').append(callData.url())
                .append("\n📊 Status: ").append(callData.statusCode())
                .append("\n🔧 Protocol: ").append(callData.protocol());
        if (matchedPattern != null) {
            out.append("\n🔍 Matched by: ").append(matchedPattern.matchType()).append(" (")
                    .append(matchedPattern.matchValue()).append(')');
        }
        if (callData.requestBody() instanceof JsonNode body && body.isObject()) {
            if (body.has("model")) {
                out.append("\n🤖 Model: ").append(body.get("model").asText());
            }
            if (body.has("messages") && body.get("messages").isArray()) {
                out.append("\n💬 Messages: ").append(body.get("messages").size());
            }
            if (body.has("temperature")) {
                out.append("\n🌡️  Temperature: ").append(body.get("temperature").asText());
            }
        }
        out.append(dryRun ? "\n🚫 DRY RUN: API call will be skipped" : "\n📤 Sending to: " + apiEndpoint);
        log.info(out.toString());
    }

    // ------------------------------------------------------------------ reads

    /**
     * Full input/output content of one log ({@code GET /api/v2/llm_request_logs/:id}). Requires the
     * private API key.
     *
     * @throws IllegalArgumentException if {@code logId} is blank or a dot-segment, or {@code searchQuery} is blank
     */
    public CompletableFuture<JsonNode> getLogContentAsync(String logId, GetLogContentOptions opts) {
        String url = buildResourceUrl(logId, "getLogContent: logId must be a non-empty string");
        GetLogContentOptions o = opts == null ? GetLogContentOptions.NONE : opts;
        Query q = new Query();
        q.set("section", o.section() == null ? null : o.section().wireName());
        q.set("max_chars", o.maxChars());
        if (o.searchQuery() != null) {
            if (JsText.isBlank(o.searchQuery())) {
                throw new IllegalArgumentException("getLogContent: searchQuery must be a non-empty string");
            }
            q.set("search_query", o.searchQuery());
        }
        q.set("include_thinking", o.includeThinking());
        q.set("include_turn_metrics", o.includeTurnMetrics());
        return getJson(q.appendTo(url), "Log");
    }

    public JsonNode getLogContent(String logId, GetLogContentOptions opts) {
        return await(getLogContentAsync(logId, opts));
    }

    /**
     * Searches logs by named filters. Pagination is read from response headers when present and
     * estimated from the page otherwise (pass {@code includeTotal} for exact totals).
     */
    public CompletableFuture<Page<JsonNode>> searchLogsAsync(SearchLogsParams params) {
        SearchLogsParams p = params == null ? SearchLogsParams.NONE : params;
        Query q = new Query()
                .set("template_id", p.templateId())
                .set("workload_id", p.workloadId())
                .set("system_prompt_contains", p.systemPromptContains())
                .set("user_prompt_contains", p.userPromptContains())
                .set("model", p.model())
                .set("source_api", p.sourceApi())
                .set("source_api_result", p.sourceApiResult())
                .set("source_application", p.sourceApplication())
                .set("project_path", p.projectPath())
                .set("unmatched_only", p.unmatchedOnly())
                .set("days_back", p.daysBack())
                .set("since", toTimestampParam(p.since()))
                .set("until", toTimestampParam(p.until()))
                .set("min_cost", p.minCost())
                .set("order", p.order())
                .set("include_prompts", p.includePrompts())
                .set("q[s]", p.sort())
                .set("page", p.page())
                .set("per", p.per())
                .set("include_total", p.includeTotal());
        return listPage(q.appendTo(apiEndpoint), "Log", p.page(), p.per());
    }

    public Page<JsonNode> searchLogs(SearchLogsParams params) {
        return await(searchLogsAsync(params));
    }
}
