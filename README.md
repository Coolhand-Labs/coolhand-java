# Coolhand Java SDK

Monitor and log LLM API calls from Java applications to the [Coolhand](https://coolhandlabs.com)
analytics platform: OpenAI, Anthropic, Google AI (Gemini), Vertex AI, Azure OpenAI, Azure AI
Services and AI Foundry, Amazon Bedrock, GitHub Models, OpenRouter, OpenCode, Cloudflare AI
Gateway, DeepSeek, Mistral, Perplexity, xAI, Cohere, Ollama, ElevenLabs, TypeSafe Jev and more.
Capture works with `java.net.http.HttpClient`, OkHttp, the Anthropic Java SDK and the OpenAI Java
SDK, and the same client submits feedback, declares workloads and reads logs, feedback, templates
and workloads back.

`coolhand-java` is the Java member of the Coolhand SDK family. Its behaviour, wire format,
provider patterns and redaction follow [coolhand-node](https://github.com/Coolhand-Labs/coolhand-node)
(the reference implementation, 0.17.1), with a Java API: builders, records, `Optional`,
`CompletableFuture` and unchecked exceptions.

## Related Packages

| Package | Environment | Purpose |
|---------|-------------|---------|
| `coolhand-java` | JVM (Java 17+) | Server-side monitoring and logging of LLM API calls |
| [`coolhand-node`](https://github.com/Coolhand-Labs/coolhand-node) | Node.js | The same, for Node.js |
| [`coolhand`](https://github.com/Coolhand-Labs/coolhand-python) (Python) | Python | The same, for Python |
| [`coolhand`](https://github.com/Coolhand-Labs/coolhand-js) (browser) | Browser | Feedback widget for collecting user sentiment on AI outputs |

## Installation

Java 17 or later. Maven:

```xml
<dependency>
  <groupId>com.coolhandlabs</groupId>
  <artifactId>coolhand-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle: `implementation("com.coolhandlabs:coolhand-java:0.1.0")`.

The only required dependency is Jackson (`jackson-databind`). The integrations use the libraries you
already have: `com.anthropic:anthropic-java` (3.5.0+), `com.openai:openai-java` (4.90.0+) and
`com.squareup.okhttp3:okhttp` (4.12.0+) are optional dependencies, so add whichever you use.

## Getting Started

1. **Get an API key** at [coolhandlabs.com](https://coolhandlabs.com/).
2. **Add the dependency** above.
3. **Set `COOLHAND_API_KEY`** in your environment.
4. **Send your LLM traffic through a monitored client** (see below).
5. **Deploy**: your LLM calls now show up in Coolhand.

## Quick Start

Java has no way to patch every HTTP client in a process, so monitoring is explicit: you build
your LLM client through one of the integrations. With `COOLHAND_API_KEY` set, the integrations
use a process-wide client created from the environment (the equivalent of coolhand-node's
`auto-monitor`); without it they hand back plain, unmonitored clients, so the same code runs with
and without Coolhand.

### Anthropic Java SDK

```java
import com.anthropic.client.AnthropicClient;
import com.coolhandlabs.coolhand.integrations.anthropic.CoolhandAnthropic;

AnthropicClient client = CoolhandAnthropic.client(b -> b.maxRetries(3));
// b is the SDK's AnthropicOkHttpClient.Builder, after fromEnv(): set apiKey, baseUrl, timeout, ...
Message message = client.messages().create(params);
```

Already building your own client? Add the interceptor:
`AnthropicOkHttpClient.builder().fromEnv().addInterceptor(CoolhandAnthropic.interceptor()).build()`.

### OpenAI Java SDK

```java
import com.coolhandlabs.coolhand.integrations.openai.CoolhandOpenAI;

OpenAIClient client = CoolhandOpenAI.client(b -> b.maxRetries(3)); // b is ClientOptions.Builder, after fromEnv()
```

### java.net.http.HttpClient

```java
import com.coolhandlabs.coolhand.integrations.httpclient.CoolhandHttpClient;

HttpClient http = CoolhandHttpClient.wrap(HttpClient.newHttpClient());
```

### OkHttp (and libraries that accept your own OkHttp client)

```java
import com.coolhandlabs.coolhand.integrations.okhttp.CoolhandInterceptor;

OkHttpClient http = new OkHttpClient.Builder().addInterceptor(new CoolhandInterceptor()).build();
```

### Explicit client (no environment variables)

```java
Coolhand coolhand = new Coolhand(CoolhandOptions.builder()
        .apiKey(System.getenv("COOLHAND_API_KEY"))
        .build());

HttpClient http = CoolhandHttpClient.wrap(HttpClient.newHttpClient(), coolhand);
AnthropicClient anthropic = CoolhandAnthropic.client(coolhand, b -> b.maxRetries(3));
OkHttpClient okhttp = new OkHttpClient.Builder().addInterceptor(new CoolhandInterceptor(coolhand)).build();
```

Or make an explicit client the process-wide one (coolhand-node's `initializeGlobalMonitoring`):
`Coolhand.initializeGlobalMonitoring(options)`. `Coolhand.isConfigured()` tells you whether
Coolhand can log (a global client exists or `COOLHAND_API_KEY` is set).

A `Coolhand` client is thread-safe and meant to be shared. Logs are submitted in the background;
call `coolhand.flush(Duration)` (or `close()`) before a short-lived process exits.

See [docs/global-monitoring.md](docs/global-monitoring.md) for what each integration captures,
streaming behaviour, redaction and troubleshooting.

## Feedback API

```java
Optional<FeedbackResponse> created = coolhand.createFeedback(LlmRequestLogFeedback.builder()
        .llmProviderUniqueId(message.id())          // e.g. Anthropic's msg_..., OpenAI's chatcmpl-...
        .sentiment(Sentiment.DISLIKE)
        .explanation("Missed the boundary case")
        .creatorType(CreatorType.HUMAN)
        .build());

created.map(FeedbackResponse::llmRequestLogId);     // the matched log, when one matched
```

`createFeedback` never throws for API or network failures: it returns `Optional.empty()` (and logs
the error), as coolhand-node resolves to `null`. Match feedback to a log by `llmRequestLogId`,
`llmProviderUniqueId`, `originalOutput` (fuzzy) or `clientUniqueId`.
[docs/feedback.md](docs/feedback.md) has every field, sentiment conversion and the asynchronous
variant.

### Reading Feedback

```java
Page<JsonNode> page = coolhand.searchFeedback(SearchFeedbackParams.builder()
        .sentimentEq(0).explanationCont("too long").sort("created_at desc").per(50).build());
JsonNode one = coolhand.getFeedback(page.items().get(0).get("id").asText());
```

Read methods need the **private** API key.

## Reading Logs

```java
Page<JsonNode> logs = coolhand.searchLogs(SearchLogsParams.builder()
        .workloadId("wkl_123").since(Instant.now().minus(Duration.ofDays(7))).includeTotal(true).build());
JsonNode content = coolhand.getLogContent(logs.items().get(0).get("id").asText(),
        GetLogContentOptions.builder().searchQuery("refund").build());
```

See [docs/log-search.md](docs/log-search.md).

## Reading Templates

```java
Page<JsonNode> templates = coolhand.searchTemplates(SearchTemplatesParams.builder()
        .search("parser").metrics(MetricsWindow.include(true).withDaysBack(30)).build());
JsonNode template = coolhand.getTemplate("tpl_abc"); // includes the prompt patterns
```

See [docs/template-search.md](docs/template-search.md).

## Reading Step Templates

```java
Page<JsonNode> steps = coolhand.listStepTemplates(new ListStepTemplatesParams(null, "tool_step", true, null, 25));
JsonNode step = coolhand.getStepTemplate(42);
```

See [docs/step-templates.md](docs/step-templates.md).

## Reading Workloads and Cost Metrics

```java
Page<JsonNode> workloads = coolhand.searchWorkloads(SearchWorkloadsParams.builder()
        .includeTemplates(true).metrics(MetricsWindow.include(true).withDaysBack(7)).build());
```

See [docs/workload-search.md](docs/workload-search.md).

## Reading the Help Center

```java
Page<JsonNode> articles = coolhand.searchHelpArticles(SearchHelpArticlesParams.query("feedback"));
JsonNode article = coolhand.getHelpArticle("feedback-api");
List<JsonNode> sections = coolhand.listHelpCategories();

new HelpCenterDocsService().searchHelpArticles(SearchHelpArticlesParams.query("logs")); // no key needed
```

See [docs/help-center.md](docs/help-center.md).

## Reading Referenced Files

```java
Page<JsonNode> files = coolhand.searchReferencedFiles(new SearchReferencedFilesParams("app/models/", null, null, null, null));
Page<JsonNode> sessions = coolhand.listReferencedFileSessions(ListReferencedFileSessionsParams.of("app/models/user.rb"));
```

See [docs/llm-reference-search.md](docs/llm-reference-search.md).

## Client Files

```java
coolhand.uploadClientFile(ClientFileUpload.of("Q3 review", Files.readAllBytes(path), "q3.pdf"));
Page<ClientFile> files = coolhand.listClientFiles(ListClientFilesParams.NONE);
String id = files.items().get(0).id();
DownloadedClientFile file = coolhand.downloadClientFile(id);
coolhand.updateClientFileStatus(id, "published");
```

See [docs/client-file-upload.md](docs/client-file-upload.md).

### Link Feedback to an Optimization

```java
coolhand.linkFeedback("opt_1", "fb_1", "evidence for the shorter prompt");
coolhand.bulkLinkFeedback("opt_1", feedbackIds); // batches of 100, counts merged
```

See [docs/feedback-links.md](docs/feedback-links.md).

### Declare Workloads and Templates

```java
Classification result = Coolhand.withContext(
        DeclaredContext.builder()
                .reporter("support-app")
                .workload("support/triage", "Ticket triage", "Classifies and routes support tickets")
                .template("support/triage/classify", "Classify")
                .build(),
        () -> triage.classify(ticket));     // returns the block's value; its exceptions propagate
```

Every LLM call captured on this thread while the block runs is sent with
`llm_request_log.declared`, so Coolhand skips template matching. The declaration is validated
before the block runs (`IllegalArgumentException`). See
[docs/declared-workloads.md](docs/declared-workloads.md), including `upsertWorkload` /
`upsertTemplate` and how to carry the context to other threads.

## Configuration Options

| `CoolhandOptions` builder | Default | Meaning |
|---|---|---|
| `apiKey(String)` | (required) | Coolhand API key, sent as `X-API-Key` |
| `silent(boolean)` | `true` | Progress logging off. Errors are still logged. |
| `debug(boolean)` | `false` | Verbose logging. Never suppresses submissions (use `dryRun`). |
| `dryRun(boolean)` | `false` | Skip every write to Coolhand; the payload is logged instead when not silent. |
| `baseUrl(String)` | `https://coolhandlabs.com` | Self-hosted endpoint. https only, except `http://localhost`, `127.0.0.1`, `[::1]`. |
| `requestTimeout(Duration)` | 30 s | Timeout for Coolhand API calls (uploads: 10 min). |
| `patternsFile(Path)` | bundled | Custom provider patterns, replacing the bundled `api-patterns.json`. |
| `excludeApiPatterns(List<String>)` | `Coolhand.DEFAULT_EXCLUDE_API_PATTERNS` | URL substrings never captured; see [docs/exclude-patterns.md](docs/exclude-patterns.md). |
| `captureIdleTimeout(Duration)` | 30 s | A response body unread this long is logged with what was read. |
| `httpClient(HttpClient)` | shared | The `java.net.http.HttpClient` used to reach Coolhand (must not follow redirects). |

### Environment Variables

Read by `CoolhandOptions.fromEnv()` and by the process-wide client:

| Variable | Default | Meaning |
|---|---|---|
| `COOLHAND_API_KEY` | | API key; enables the process-wide client |
| `COOLHAND_SILENT` | `true` | Anything but `false` is silent |
| `COOLHAND_DEBUG` | `false` | `true` enables verbose logging |
| `COOLHAND_DRY_RUN` | `false` | `true` suppresses all submissions |
| `COOLHAND_BASE_URL` | | Self-hosted endpoint |
| `COOLHAND_PATTERNS_FILE` | | Custom patterns file |
| `COOLHAND_EXCLUDE_API_PATTERNS` | | Comma-separated URL substrings; a value with no non-blank entry keeps the defaults |

Logging goes through `System.Logger` (logger `com.coolhandlabs.coolhand`), so it lands in whatever
backend your application routes the platform logger to (java.util.logging, SLF4J, Log4j).

## What Gets Logged

For each captured call: method, URL (credential query parameters and `user:pass@` redacted),
request headers and response headers (credentials redacted), request body and response body
(parsed as JSON where possible, credentials redacted, capped at 50 MiB, compressed bodies
decoded), status code, and `stream_incomplete: true` when the host stopped reading before the end.
The exact payload, `POST /api/v2/llm_request_logs`:

```json
{
  "llm_request_log": {
    "raw_request": {
      "id": 1,
      "timestamp": "2026-11-05T10:15:30.123Z",
      "method": "POST",
      "url": "https://api.anthropic.com/v1/messages",
      "headers": { "anthropic-version": "2023-06-01", "content-type": "application/json", "x-api-key": "[REDACTED]" },
      "request_body": { "model": "claude-sonnet-4-5", "max_tokens": 1024, "messages": [ ... ] },
      "response_body": { "id": "msg_01...", "content": [ ... ], "usage": { ... } },
      "response_headers": { "content-type": "application/json", "request-id": "req_..." },
      "status_code": 200,
      "protocol": "https"
    },
    "collector": "coolhand-java-0.1.0-global-monitoring",
    "declared": { "reporter": "support-app", "workload": { "key": "support/triage", "name": "...", "description": "..." },
                  "template": { "key": "support/triage/classify", "name": "Classify" } }
  }
}
```

`declared` is present only inside `withContext`; `metadata` only for manual submissions that pass
it. Feedback is `POST /api/v2/llm_request_log_feedbacks` with
`{"llm_request_log_feedback": {..., "collector": "coolhand-java-0.1.0-manual"}}`.

## Supported Integrations

- **Anthropic Java SDK** (`com.anthropic:anthropic-java` 3.5.0+): `CoolhandAnthropic.client(...)`,
  `CoolhandAnthropic.interceptor()`, or `CoolhandAnthropicHttpClient` around any SDK `HttpClient`.
  Sync, async and streamed (`createStreaming`) calls.
- **OpenAI Java SDK** (`com.openai:openai-java` 4.90.0+): `CoolhandOpenAI.client(...)` or
  `CoolhandOpenAIHttpClient`.
- **`java.net.http.HttpClient`**: `CoolhandHttpClient.wrap(...)`, any `BodyHandler`, `send` and `sendAsync`.
- **OkHttp** 4.x: `CoolhandInterceptor`, which also covers libraries that let you supply your own
  OkHttp client (Retrofit-based clients, for example).
- **Anything else**: `coolhand.monitor()` exposes the capture engine (`RequestMonitor`,
  `CaptureSession`, `ResponseCapture`) for a custom HTTP client; see
  [docs/global-monitoring.md](docs/global-monitoring.md#writing-your-own-integration).

Recognised providers are those in [`api-patterns.json`](src/main/resources/com/coolhandlabs/coolhand/api-patterns.json),
identical to coolhand-node's. Requests to anything else pass through untouched.

## Custom AI Providers

Point `patternsFile` (or `COOLHAND_PATTERNS_FILE`) at a JSON file shaped like the bundled one:

```json
{
  "patterns": [
    { "name": "Internal Gateway", "domains": ["llm.internal.example.com"],
      "headers": { "x-gateway-key": "[REDACTED]" } }
  ]
}
```

`domains` match the host and its subdomains (`*` stands for one DNS label); `paths` with
`requiresPathMatch: true` restrict a pattern to those path prefixes, and `ports` then identify a
host-less provider such as Ollama on 11434. A custom file replaces the bundled patterns; a missing
or malformed file falls back to them.

## Monitoring Statistics

```java
CoolhandStats stats = coolhand.getStats();   // totalRequests, interceptedCalls, apiEndpoint
CoolhandStats global = Coolhand.getGlobalStats();
```

## Testing Your Integration

`com.coolhandlabs.coolhand.testing.CoolhandTestUtils` (coolhand-node's `test-utils`) proves in
your own tests that a code path's LLM calls really go through a monitored client:

```java
InterceptionSnapshot before = CoolhandTestUtils.captureInterceptionSnapshot(coolhand);
summarizer.summarize(text);
CoolhandTestUtils.assertInterceptionOccurred(coolhand, before);   // AssertionError otherwise
```

Combine it with `dryRun(true)` so tests never submit anything.

## Dry-Run Mode

`dryRun(true)` (or `COOLHAND_DRY_RUN=true`) captures and redacts as usual but never sends:
`logRequest`, `createFeedback`, uploads, upserts, links and MCP calls return `Optional.empty()`.
With `silent(false)` the payload that would have been sent is logged. Read methods still run.

## Debug Mode (Verbose Logging)

`debug(true)` with `silent(false)` logs the endpoint and payload size of every submission. It does
not suppress anything.

## Manual Submission

```java
coolhand.logRequest(CallData.builder()
        .url("https://api.anthropic.com/v1/messages").method("POST")
        .requestBody(requestJson).responseBody(responseJson).statusCode(200).build(),
        "my-replayer-1.0", Map.of("project_path", "/srv/app"));
```

See [docs/manual-submission.md](docs/manual-submission.md).

## Error Handling

| Kind | Methods | On failure |
|---|---|---|
| Submissions | `logRequest`, `createFeedback`, `uploadClientFile` | `Optional.empty()`; error logged, never thrown |
| Reads and admin writes | `search*`, `get*`, `list*`, `download*`, `update*`, `upsert*`, `link*`, `unlink*` | `CoolhandHttpException` (non-2xx, `status()`), `CoolhandException` (network, timeout, redirect, bad JSON) |
| Invalid arguments | all | `IllegalArgumentException` before any request |
| Captured calls | integrations | never affect the host call; logging failures are logged |

All three exception types are unchecked. Messages never contain the API key. Each service
(`coolhand.feedbackService()`, `loggingService()`, ...) also offers every method as
`...Async`, returning a `CompletableFuture` that completes exceptionally with the same exceptions.

## API Key

Logging and feedback work with the public key. Reads, upserts, links and uploads need the
**private** key (client-file administration needs a client admin's per-user key). The key is
validated at construction (blank keys and keys with characters invalid in an HTTP header are
rejected) and trimmed of surrounding whitespace.

## Differences from coolhand-node

Not applicable in Java, and therefore not ported:

- **Global patching** of `http`/`https`/`fetch`, `auto-monitor` import side effects, ESM/CJS
  builds, `diagnostics_channel`, Edge runtime support and `loadPatterns()` (patterns always load
  synchronously). Java integrations are explicit, per client.
- **The 1-second de-duplication window** of the auto-monitor (a Node artefact of two patching
  layers seeing one request).
- **Framework guides** (Express, Next.js, ...); the integrations above cover the Java HTTP stacks.
- Deprecated shims (`environment`, the old `debug` semantics).

Behavioural differences:

- **Context propagation**: `withContext` uses a `ThreadLocal`, not `AsyncLocalStorage`. The
  integrations snapshot it on the thread that sends the request; use `Coolhand.wrapWithContext`
  for work you hand to other threads, and `CoolhandInterceptor.tagCurrentContext` for OkHttp
  `enqueue`.
- **Collector**: calls captured by the integrations use `coolhand-java-<version>-global-monitoring`
  (coolhand-node's instance-based monitor reports `manual` for them).
- **`protocol`** is the URL scheme (`https`/`http`); coolhand-node also reports `fetch`.
- **Early close of a non-streamed body**: Java JSON parsers stop at the end of the value without
  reading to end of input, so when the host closes a non-streamed body early the capture reads the
  rest (up to 50 MiB) and logs a complete call. Streamed bodies (SSE, NDJSON, AWS event streams)
  closed early are logged as incomplete, as in coolhand-node.
- **Request bodies** of captured calls are read in full before sending (so they can be logged and
  sent); coolhand-node streams them.
- **Read results** are Jackson `JsonNode`s inside typed envelopes (`Page`, `Pagination`,
  `FeedbackResponse`, `LogResponse`, ...), so new server fields are never lost.
- **Brotli** (`br`) responses are decoded only when `org.brotli:dec` is on the classpath;
  otherwise they are logged as received.

## Security

Credentials are redacted before anything leaves the process: headers (`authorization`,
`x-api-key`, cookies and any credential-looking name), URL query parameters and userinfo, and
request/response bodies (credential keys in JSON, NDJSON, form-encoded and multipart bodies, Azure
"On Your Data" datastore credentials, MCP server tokens). The Coolhand API key never appears in
logs or exception messages, and requests to Coolhand never follow redirects. See
[SECURITY.md](SECURITY.md) to report a vulnerability.

## Documentation

- [Monitoring and integrations](docs/global-monitoring.md)
- [Declared workloads and templates](docs/declared-workloads.md)
- [Exclude patterns](docs/exclude-patterns.md)
- [Manual submission](docs/manual-submission.md)
- [Feedback](docs/feedback.md) and [feedback links](docs/feedback-links.md)
- [Log search](docs/log-search.md), [template search](docs/template-search.md),
  [step templates](docs/step-templates.md), [workloads](docs/workload-search.md),
  [referenced files](docs/llm-reference-search.md)
- [Client files](docs/client-file-upload.md)
- [Help center](docs/help-center.md)

## Building

```bash
mvn -B verify
```

compiles for Java 17, runs the tests (no network: local servers stand in for the providers and
the Coolhand API) and builds the main, sources and javadoc jars. CI runs it on Java 17, 21 and 25.

## Publishing to Maven Central

The POM carries the metadata Central requires (name, description, URL, license, SCM, developers)
and the build attaches sources and javadoc jars. To publish a release, Coolhand Labs needs to:

1. Register the `com.coolhandlabs` namespace on the [Central Portal](https://central.sonatype.com)
   (verify domain ownership with a DNS TXT record) and generate a publishing token.
2. Create a GPG key, publish its public half to a key server, and add signing: the
   `maven-gpg-plugin` (`sign` goal in the `verify` phase), ideally in a `release` profile.
3. Add the `org.sonatype.central:central-publishing-maven-plugin` with the token configured as a
   `server` in `settings.xml` (or CI secrets).
4. Run `mvn -P release deploy`, or add a tag-triggered GitHub Actions workflow that does so with
   the token and GPG key as repository secrets.

## About Coolhand Labs

Coolhand Labs helps teams understand and improve the LLM features they ship.
[coolhandlabs.com](https://coolhandlabs.com)

## License

Apache License 2.0; see [LICENSE](LICENSE).
