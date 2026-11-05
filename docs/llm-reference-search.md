# Referenced Files

Files referenced in captured sessions, last 90 days (private API key).

```java
Page<JsonNode> files = coolhand.searchReferencedFiles(
        new SearchReferencedFilesParams("app/models/", "2026-10-01", null, 1, 50));
// one row per file_path: file_path, reference_count, last_referenced_at; most referenced first

Page<JsonNode> sessions = coolhand.listReferencedFileSessions(
        new ListReferencedFileSessionsParams("app/models/user.rb", 1, 25));
// raw rows for that exact path, newest first: llm_request_log_id, created_at
```

- Filters are Ransack: `q[file_path_cont]`, `q[created_at_gteq]`, `q[created_at_lteq]`. The client
  comes from the API key.
- `filePath` is an exact match, sent as the `file_path` query parameter of
  `GET /api/v2/llm_references/sessions` (file paths are not URL-safe path segments). An unmatched
  path is an empty page; a missing one throws `IllegalArgumentException`.
- Pass a session's `llm_request_log_id` to `getLogContent` to inspect it.
- `504` means the aggregate (or the count for a very common path) hit the statement timeout; retry.
