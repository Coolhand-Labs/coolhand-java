# Workload Search

List workloads (the groups of templates that make up one task or agent), optionally with a
cost/performance rollup computed like the dashboard's (private API key).

```java
Page<JsonNode> page = coolhand.searchWorkloads(SearchWorkloadsParams.builder()
        .search("triage")
        .includeArchived(false)
        .includeSystem(false)
        .includeTemplates(true)
        .metrics(MetricsWindow.include(true).withDaysBack(7))
        .build());
```

- Query parameters: `search`, `include_archived`, `include_system`, `include_templates`,
  `include_metrics`, `days_back`, `since`, `until`, `page`, `per`; ordered by name.
- Each `id` is the workload hashid: the stable key, and what `workloadId` on template and log
  search expects.
- `422` for a malformed metrics window, `504` when an aggregate exceeds the statement timeout.
- Creating and renaming by key: `coolhand.upsertWorkload(...)`, see
  [declared-workloads.md](declared-workloads.md#creating-and-renaming-explicitly).
