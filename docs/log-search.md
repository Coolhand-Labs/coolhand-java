# Log Search

Read logs back (private API key).

## Searching

```java
Page<JsonNode> page = coolhand.searchLogs(SearchLogsParams.builder()
        .workloadId("wkl_1")
        .model("claude-sonnet-4-5")
        .since(Instant.parse("2026-10-01T00:00:00Z"))   // or an ISO-8601 String, passed through
        .minCost(0.05)
        .order("cost_desc")
        .includePrompts(true)
        .page(1).per(50)
        .includeTotal(true)
        .build());
```

| Builder method | Query parameter |
|---|---|
| `templateId`, `workloadId` | `template_id`, `workload_id` |
| `systemPromptContains`, `userPromptContains` | `system_prompt_contains`, `user_prompt_contains` |
| `model`, `sourceApi`, `sourceApiResult`, `sourceApplication` | `model`, `source_api`, `source_api_result`, `source_application` |
| `projectPath` | `project_path` (the `metadata.project_path` of manual submissions) |
| `unmatchedOnly` | `unmatched_only` |
| `daysBack`, `since`, `until` | `days_back`, `since`, `until` (`Instant`s as `2026-10-01T00:00:00.000Z`) |
| `minCost`, `order` | `min_cost`, `order` (`cost_desc`); priceable logs only |
| `includePrompts` | `include_prompts` |
| `sort` | `q[s]` (Ransack sort, e.g. `created_at desc`) |
| `page`, `per`, `includeTotal` | `page`, `per`, `include_total` |

`false` booleans are sent as `false`, never dropped. Each item carries `cost` (USD, `null` when it
cannot be priced).

### Pagination

The endpoint returns a bare array; `pagination()` comes from the `X-Page`, `X-Per-Page`,
`X-Total-Count` and `X-Total-Pages` headers. Without `includeTotal`, totals may be absent, and the
SDK reports a lower-bound estimate instead of zero: earlier pages are assumed full,
`hasNextPage` is true when the page is full, and an empty page reports zero totals. Malformed
header values are treated as absent.

## Reading one log

```java
JsonNode full = coolhand.getLogContent("log_hashid");
JsonNode tail = coolhand.getLogContent("log_hashid", GetLogContentOptions.builder()
        .section(GetLogContentOptions.Section.END).maxChars(2000).build());
JsonNode hits = coolhand.getLogContent("log_hashid", GetLogContentOptions.builder()
        .searchQuery("refund").includeThinking(true).includeTurnMetrics(true).build());
```

- `section` takes effect only with `maxChars`; `searchQuery` returns up to 5 snippets per field and
  cannot be combined with `section`/`maxChars` (rejected when the options are built).
- `includeTurnMetrics` adds `turn_metrics` (captured agent sessions; `null` otherwise).
- Blank ids, `.`/`..` and a blank `searchQuery` throw `IllegalArgumentException` before any request.
- A non-2xx response throws `CoolhandHttpException` (404 unknown id, 422 bad window, cost or order).

## IDs

Ids are hashids. The `LogResponse.id()` of a submission is the raw id the server returned, as text.
