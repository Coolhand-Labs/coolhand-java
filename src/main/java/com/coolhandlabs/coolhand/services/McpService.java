package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.CoolhandException;
import com.coolhandlabs.coolhand.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Calls a tool on Coolhand's MCP endpoint ({@code POST /mcp}, JSON-RPC 2.0 {@code tools/call}). */
public final class McpService extends BaseService {
    public McpService(ServiceConfig config) {
        super(config, "/mcp");
    }

    /**
     * Calls {@code toolName} and returns the JSON-RPC {@code result}; empty on a dry run.
     *
     * @throws CoolhandException on a JSON-RPC error, a tool-level {@code result.error}, a network
     *     failure or a non-JSON body; {@link com.coolhandlabs.coolhand.CoolhandHttpException} on non-2xx
     */
    public CompletableFuture<Optional<JsonNode>> mcpCallAsync(String toolName, Map<String, ?> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args == null ? Map.of() : args);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "tools/call");
        body.put("params", params);
        if (dryRun) {
            if (!silent) {
                log.info("🚫 DRY RUN: Skipping MCP call to " + apiEndpoint);
                log.info("🚫 DRY RUN: Would send: " + Json.pretty(body));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest request = request(apiEndpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        return fetchOrThrow(request, "MCP request failed").thenApply(text -> {
            JsonNode json;
            try {
                json = Json.parse(text);
            } catch (Exception e) {
                throw new CoolhandException("MCP response was not valid JSON: " + excerpt(text));
            }
            if (json == null || !json.isObject()) {
                throw new CoolhandException("MCP response was not a JSON object: " + excerpt(text));
            }
            JsonNode error = json.get("error");
            if (error != null && !error.isNull() && !(error.isBoolean() && !error.booleanValue())) {
                JsonNode message = error.get("message");
                throw new CoolhandException("MCP error: "
                        + mask(message != null && !message.isNull() ? message.asText() : error.toString()));
            }
            JsonNode result = json.get("result");
            if (result != null && result.isObject()) {
                JsonNode toolError = result.get("error");
                if (toolError != null && toolError.isTextual() && !toolError.asText().isEmpty()) {
                    throw new CoolhandException(mask(toolError.asText()));
                }
            }
            return Optional.ofNullable(result);
        });
    }

    public Optional<JsonNode> mcpCall(String toolName, Map<String, ?> args) {
        return await(mcpCallAsync(toolName, args));
    }
}
