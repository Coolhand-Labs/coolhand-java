# Realized Savings

`getRealizedSavings()` returns the saving your client has realized by switching templates or
models: what the requests served by the current templates cost, versus what the same requests
would have cost on the deprecated versions they replaced. It is the figure the dashboard shows.
It is read-only (private API key).

The client is always derived from the API key: there is no client id and no parameter of any kind.
The comparison covers deprecations from the last three months, fixed server-side, so there is no
`daysBack`. The result is cached server-side for an hour.

```java
JsonNode savings = coolhand.getRealizedSavings();
savings.get("total").doubleValue();   // 4.5372 (USD)
savings.get("percent").doubleValue(); // 79.7
```

The async form is `coolhand.savingsService().getRealizedSavingsAsync()`.

## Return value

```json
{
  "total": 4.5372,
  "cost_at_previous_versions": 5.6912,
  "percent": 79.7,
  "workloads": [
    {
      "id": "xwjm0lgsdmy4",
      "name": "Help Center Article Suggester",
      "savings": 2.0437,
      "cost_at_previous_versions": 3.0402,
      "active_requests": 182
    }
  ]
}
```

- `total` is the saving in USD and `cost_at_previous_versions` the baseline it is measured against,
  both rounded to 4 decimals. `percent` is `total` as a share of that baseline, to one decimal, and
  `0.0` when the baseline is 0.
- `workloads` lists only workloads with a template deprecated in the last three months. Every row
  has all five keys; `id` is the workload hashid, the same as on `searchWorkloads`.
- A client with nothing to compare gets zeros and an empty `workloads` array. That is real zero
  savings.

## Errors

A non-2xx response throws `CoolhandHttpException`, with the status on `status()`:

| Status | When |
|---|---|
| `401` | No API key, an invalid key, or the public key |
| `503` | The comparison could not be computed right now. Retryable |

**A `503` is not zero savings.** Do not map it to zeros; show it as an error and retry. Network
failures and non-JSON bodies throw `CoolhandException` without a status.

## Combining it with workload metrics

`savings` is **not windowed**, but a workload's `metrics` from `searchWorkloads` with `daysBack` are.
A workload's `savings` can therefore exceed its windowed `metrics.total_cost`; do not read the two as
a ratio over the same period. Join them on `id` to build a per-workload ROI view, the same join the
`get_client_roi_summary` MCP tool performs over the two endpoints:

```java
// Archived and system workloads can still carry realized savings, so include them or their rows go unmatched.
Page<JsonNode> workloads = coolhand.searchWorkloads(SearchWorkloadsParams.builder()
        .metrics(MetricsWindow.include(true).withDaysBack(30))
        .includeArchived(true).includeSystem(true).per(100).build());
JsonNode savings = coolhand.getRealizedSavings();

Map<String, JsonNode> savingsById = new HashMap<>();
savings.get("workloads").forEach(row -> savingsById.put(row.get("id").asText(), row));

for (JsonNode workload : workloads.items()) {
    JsonNode row = savingsById.get(workload.get("id").asText());
    double spendLast30Days = workload.path("metrics").path("total_cost").asDouble(); // windowed
    double realizedSavings = row == null ? 0 : row.get("savings").doubleValue();      // not windowed
}
```

This reads one page of up to 100 workloads; repeat the call with the next `page` while
`workloads.pagination().hasNextPage()` is true for a client with more.
