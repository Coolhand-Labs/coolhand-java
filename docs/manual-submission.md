# Manual Submission

`Coolhand.logRequest` submits a request/response that did not flow through an integration, for
example replayed or imported sessions.

```java
CallData call = CallData.builder()
        .id(1)
        .timestamp(Instant.parse("2026-11-05T10:15:30.123Z"))
        .method("POST")
        .url("https://api.anthropic.com/v1/messages")
        .header("content-type", "application/json")
        .requestBody(mapper.readTree(requestJson))     // a JsonNode, Map or String
        .responseBody(mapper.readTree(responseJson))
        .statusCode(200)
        .build();

Optional<LogResponse> log = coolhand.logRequest(call);
Optional<LogResponse> withSource = coolhand.logRequest(call, "my-importer-1.2.0", Map.of("project_path", "/srv/app"));
```

- `collector` (may be null) replaces the SDK's `coolhand-java-<version>-manual` string, to identify
  your tool.
- `metadata` (may be null) is sent as `llm_request_log.metadata`; the backend's one convention is
  `project_path`.
- The declared context active on the calling thread is sent as `declared`.
- **Nothing is redacted** for you here: you build the record, so strip credentials first
  (`coolhand.sanitizeHeaders(...)` and `coolhand.patternMatchingService().sanitizeBody(...)` apply
  the same rules as the integrations).
- Never throws for API or network failures: the result is empty then, and in dry-run mode.
  `logRequestAsync` returns a `CompletableFuture`.
- `streamIncomplete(true)` marks a response that was cut off; it is sent only when true.

The returned `LogResponse` carries the server's `id`, `llmProviderUniqueId` and any `warnings`.
