# Exclude Patterns

`excludeApiPatterns` lists URL **substrings** that are never captured, even when the URL matches a
provider pattern. They are plain substrings, not globs or regular expressions: `/internal-*`
matches only URLs that literally contain `/internal-*` (the SDK logs a warning for any entry
containing `*`).

```java
Coolhand coolhand = new Coolhand(CoolhandOptions.builder()
        .apiKey(key)
        .excludeApiPatterns(List.of("/v1/batches", "internal.corp"))
        .build());

coolhand.setExcludeApiPatterns(List.of("/v1/files"));   // replace at runtime
```

Or `COOLHAND_EXCLUDE_API_PATTERNS=/v1/batches,internal.corp` (comma-separated; blank entries are
dropped, and a value with no non-blank entry keeps the defaults).

## Defaults

Unset, the list is `Coolhand.DEFAULT_EXCLUDE_API_PATTERNS`: Vertex AI batch prediction jobs and
the Azure OpenAI control-plane paths (`/openai/files`, `/openai/batches`, `/openai/fine_tuning`,
`/openai/models`, each with and without `/v1`). Setting the option **replaces** the defaults; an
empty list excludes nothing. Keep the defaults by adding to them:

```java
List<String> exclude = new ArrayList<>(Coolhand.DEFAULT_EXCLUDE_API_PATTERNS);
exclude.add("/internal/");
```

## Matching

An entry matches the URL as given, or its canonical form: host lowercased, one trailing host dot
dropped, and an empty path written as `/`. So `internal.corp` excludes `https://Internal.Corp/v1`
and `https://internal.corp./v1`, and `example.com/` excludes `https://example.com`. Paths and
queries stay case-sensitive. Write host entries in lowercase. Empty entries are ignored (they would
match everything).

The Coolhand API itself is always excluded, by host and port, whatever this list says.
