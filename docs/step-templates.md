# Step Templates

Step templates are the skills and other recurring steps Coolhand detects in captured agent
sessions (private API key).

```java
Page<JsonNode> page = coolhand.listStepTemplates(
        new ListStepTemplatesParams("lint", "tool_step", true, 1, 25)); // search, stepType, includeMetrics, page, per
JsonNode step = coolhand.getStepTemplate(42);           // metrics included by default
JsonNode bare = coolhand.getStepTemplate(42, false);
```

- `stepType` is `prompt_step` or `tool_step`; an unknown value is a `422`.
- `includeMetrics` on the list caps `per` at 25 server-side. Metrics hold `total_runs`, tool usage,
  turn statistics and efficiency cohorts.
- Ids are positive integers; anything else throws `IllegalArgumentException`. Drafts, failed step
  templates and other clients' ids are `404`.
