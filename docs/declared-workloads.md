# Declared Workloads and Templates

By default Coolhand matches each captured call to a template by its prompt. If you already know
which workload and template a call belongs to, **declare** it: run the call inside
`Coolhand.withContext` and the log is sent with that identity, skipping log-to-template matching.

```java
Summary summary = Coolhand.withContext(
        DeclaredContext.builder()
                .reporter("my-app")
                .workload(DeclaredWorkload.ofKey("Parser#call").withName("Parser#call").withDescription("Parses user input"))
                .template(DeclaredTemplate.ofKey("Parser#call/squish:L2").withName("Parser#call · squish L2"))
                .build(),
        () -> summarizer.summarize(input));
```

`withContext` returns whatever the block returns, and the block's exceptions propagate unchanged
(checked ones included: `withContext` declares exactly what the block throws). A `Runnable`-shaped
block works too. Every LLM call captured on this thread while the block runs carries the
declaration, sent as `llm_request_log.declared`.

## What you can declare

| Field | Meaning |
|---|---|
| `reporter` | Who is declaring (e.g. `"my-app"`). |
| `workload` | `DeclaredWorkload.ofId(id)` for an existing workload, or `ofKey(key)` (find-or-created), with optional `name` and `description` (≤ 1000 characters). |
| `template` | `DeclaredTemplate.ofId(id)` or `ofKey(key)` (`id` wins), with an optional `name`. |

- An `id` references an existing record; an unknown or foreign id is a `422` and the **log is not saved**.
- A `key` is find-or-created. `name`, `description` and `reporter` apply only when the record is
  first created; ingest never renames (use the upserts below).
- A workload alone gets a derived `<workload key>/default` template.
- A template `key` needs a workload (in this or an enclosing `withContext`); a template `id` does not.
- A blank `reporter` is treated as not given.

## Nesting

Per field (`reporter`, `workload`, `template`) the **innermost** value wins. `workload` and
`template` are replaced as a whole. A block that sets a `workload` without a `template` does
**not** inherit the outer template.

```java
Coolhand.withContext(ctx(workload("Agent"), template("Agent/plan")), () -> {
    Coolhand.withContext(reporter("x"), () -> {});            // Agent + Agent/plan, reporter x
    Coolhand.withContext(template("Agent/act"), () -> {});    // Agent + Agent/act
    Coolhand.withContext(workload("Other"), () -> {});        // Other, no template
});
```

## Validation

The server drops the whole log for a bad declaration, so `withContext` checks it first and throws
`IllegalArgumentException` **before the block runs**, never from inside your LLM call:

- a workload or template needs a non-blank `id` or a `key` (a blank `id` counts as not given);
- keys: at most 255 characters (code points), no control or invisible-format characters, no lone
  surrogates, no leading/trailing whitespace, no leading, trailing or doubled `/`. `#`, `:` and `·`
  are fine;
- descriptions: at most 1000 characters;
- a template `key` with no workload in scope.

## Threads

The declaration lives in a `ThreadLocal`. The integrations snapshot it on the thread that sends
the request, so a log submitted later from a background thread keeps it. Work you hand to another
thread does not see it unless you carry it:

```java
executor.submit(Coolhand.wrapWithContext(() -> client.messages().create(params)));
```

- `java.net.http` `sendAsync`, and the Anthropic/OpenAI SDKs' async methods, snapshot on the
  calling thread.
- OkHttp `enqueue` runs interceptors on OkHttp's dispatcher threads; tag the request:
  `CoolhandInterceptor.tagCurrentContext(new Request.Builder()...)`.
- `Coolhand.currentContext()` returns the active declaration.

## Warnings and caps

If the server saves the log but ignores the declaration (public-key callers are capped at 200
declared workloads / 1,000 templates; private keys are uncapped), its `warnings` are logged at
`WARNING` unless `silent`. Nothing throws. Older servers ignore the unknown `declared` field.

## Creating and renaming explicitly

`upsertWorkload` and `upsertTemplate` (private API key) create or update by key:

```java
UpsertResult workload = coolhand.upsertWorkload(UpsertWorkloadParams.key("Parser#call")
        .withName("Parser").withDescription("Parses user input").withReporter("my-app")).orElseThrow();

coolhand.upsertTemplate(UpsertTemplateParams.key("Parser#call/squish:L2")
        .withWorkloadKey("Parser#call")        // or withWorkloadId(workload.record().get("id").asText())
        .withName("Parser · squish L2"));
```

- Only the fields you set are sent; the key is immutable and a new `name` renames.
- `created()` is true for a new record (201) and false for an update (200). The result is empty
  only in dry-run mode.
- Each `/`-separated segment of the key is percent-encoded and `/` stays literal:
  `Parser#call/squish:L2` is requested as `PUT .../by_key/Parser%23call/squish%3AL2`. Blank keys and
  empty, `.` or `..` segments are rejected before any request.
- Failures throw `CoolhandHttpException`: `401` for a missing or public key, `422` for a taken name
  and, for templates, a missing workload when creating, a different workload for an existing
  template, or a key held by a deprecated template.
