# Template Search

List LLM request templates (private API key).

```java
Page<JsonNode> page = coolhand.searchTemplates(SearchTemplatesParams.builder()
        .search("parser")
        .workloadId("wkl_1")
        .status("published")            // draft, published or failure
        .includeDeprecated(false)
        .includeSystem(false)           // the Unmatched / Ignored API Calls buckets
        .metrics(MetricsWindow.include(true).withDaysBack(30))
        .page(1).per(25)
        .build());

JsonNode template = coolhand.getTemplate("tpl_1");                          // metrics included by default
JsonNode noMetrics = coolhand.getTemplate("tpl_1", MetricsWindow.include(false));
JsonNode window = coolhand.getTemplate("tpl_1",
        MetricsWindow.include(true).between(Instant.parse("2026-10-01T00:00:00Z"), Instant.now()));
```

- Query parameters: `search`, `workload_id`, `status`, `include_deprecated`, `include_system`,
  `include_metrics`, `days_back`, `since`, `until`, `page`, `per`. Pagination comes from the
  response headers, newest first.
- `getTemplate` adds `user_prompt_pattern` and `system_prompt_pattern`. Deprecated and system
  templates are reachable by id.
- Each `metrics` object holds request and failure counts, error rate, cost totals and averages,
  token totals, latency and the correctness/sentiment/revision scores for the window.
- A `504` (`CoolhandHttpException.status()`) means the `log_count` aggregate hit the backend's
  statement timeout; it is retryable.
- Creating and renaming by key: see [declared-workloads.md](declared-workloads.md#creating-and-renaming-explicitly).
