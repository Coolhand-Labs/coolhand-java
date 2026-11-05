# Monitoring and Integrations

coolhand-node patches Node's `http`, `https` and `fetch` so every LLM call in the process is
captured. The JVM has no equivalent hook, so coolhand-java captures through **integrations**: you
build your LLM client (or HTTP client) through one of them, and every LLM call it makes is logged.

| Integration | Class | Covers |
|---|---|---|
| Anthropic Java SDK 3.5.0+ | `integrations.anthropic.CoolhandAnthropic` (factory, interceptor), `CoolhandAnthropicHttpClient` | sync, async, streaming |
| OpenAI Java SDK 4.90.0+ | `integrations.openai.CoolhandOpenAI` (factory), `CoolhandOpenAIHttpClient` | sync, async, streaming |
| `java.net.http.HttpClient` | `integrations.httpclient.CoolhandHttpClient.wrap(...)` | `send`, `sendAsync`, any `BodyHandler` |
| OkHttp 4.x | `integrations.okhttp.CoolhandInterceptor` | `execute`, `enqueue` |

## Which Coolhand client logs

Each integration takes an optional `Coolhand`. Without one it uses the **process-wide client**
(`Coolhand.global()`), resolved per request:

1. the client installed with `Coolhand.initializeGlobalMonitoring(options)`, else
2. one created once from the environment when `COOLHAND_API_KEY` is set (coolhand-node's
   `auto-monitor`), else
3. none: requests pass through unmonitored.

The factories (`CoolhandAnthropic.client`, `CoolhandOpenAI.client`) decide when the client is
built: with no Coolhand client available they return a plain SDK client.

## What is captured

A request is captured when all of these hold:

1. its URL matches a provider in `api-patterns.json` (or your `patternsFile`): the host is one of a
   pattern's `domains` or a subdomain; for patterns with `requiresPathMatch`, the path also starts
   with one of its `paths` on a segment boundary, and then a listed `port` alone can identify the
   provider (Ollama on 11434);
2. it is not the Coolhand API itself (same host and effective port as `baseUrl`; this is not
   configurable);
3. no `excludeApiPatterns` entry is a substring of its URL ([exclude-patterns.md](exclude-patterns.md));
4. it is not a non-inference Anthropic endpoint (Claude Code telemetry, MCP server directory
   listings, managed-agent work polling).

Everything else passes straight through: the integration calls the underlying client unchanged.

## How a captured call is logged

- **Request**: the declared context ([declared-workloads.md](declared-workloads.md)) is snapshotted
  on the calling thread; the body is read once (so it can be logged and sent unchanged) and up to
  50 MiB of it is kept for the log.
- **Response**: the body is copied as the host reads it; the host's data is never altered or
  delayed. When the body ends the call is logged in the background (decompressing, parsing and
  redacting off the host's thread).
- **Streaming** (server-sent events, NDJSON, AWS event streams): logged once the host has read to
  the end. If the host closes the stream early, or the connection fails, the call is logged with
  what was read and `"stream_incomplete": true`; Coolhand then nulls latency (and tokens, unless the
  final usage event arrived).
- **Other bodies closed early**: Java JSON parsers stop at the end of the value without reading to
  end of input, so the capture reads the rest of the body (up to 50 MiB) when the host closes it,
  and the call is logged complete.
- **Bodies never read**: logged with what was read after `captureIdleTimeout` (30 s) without
  activity.
- **Transport failures** (no response): nothing is logged, as in coolhand-node; the exception
  reaches the host unchanged.
- **Compression**: `gzip`, `x-gzip` and `deflate` bodies are decoded for the log;
  `br` when `org.brotli:dec` is on the classpath (OkHttp and the SDKs normally decompress before
  the integration sees the body anyway).
- **Retries**: the SDK integrations sit below the SDKs' retry layer, so each attempt is a log.

Call `coolhand.flush(Duration)` (or `close()`) before a short-lived process exits so background
submissions finish.

## What is redacted

Before anything is sent:

- **Headers**: `authorization`, `api-key`, `x-api-key`, `cookie`, `set-cookie`,
  `proxy-authorization`, `openai-api-key`, `x-goog-api-key`, `cf-aig-authorization`,
  `x-amz-security-token`, `ocp-apim-subscription-key`, `subscription-key`, `xi-api-key`, and any
  name containing `auth`, `key`, `token`, `secret`, `cookie`, `passw`, `credential`, `bearer`,
  `jwt` or `signature` (except rate-limit headers), plus each pattern's own `headers` rules.
- **URLs**: `user:pass@` and credential query parameters (`key`, `api_key`, `token`, `sig`,
  `X-Amz-Signature`, ...).
- **Bodies**: string values of credential keys anywhere (`api_key`, `password`, `access_token`,
  `client_secret`, `user[password]`, ...; object values such as tool schemas are left alone), every
  credential-like key under Azure "On Your Data" `data_sources`, MCP `authorization_token` and MCP
  tool `authorization`/`headers`, OpenAI realtime `client_secret`; NDJSON line by line;
  form-encoded and multipart credential fields; and, for bodies that are not valid JSON (truncated
  at the cap, for example), any `"<credential word>": "..."` value. Subtrees nested deeper than 64
  levels are replaced.

## Writing your own integration

For an HTTP client without an integration, use the capture engine directly:

```java
RequestMonitor monitor = coolhand.monitor();
Optional<CaptureSession> session = monitor.begin(method, url, headers);   // on the calling thread
if (session.isEmpty()) { return send(request); }                          // not an LLM call
session.get().requestBody(bodyBytes);
Response response = send(request);
ResponseCapture capture = session.get().response(response.status(), response.headers());
return response.withBody(capture.wrap(response.body()));                  // an InputStream tee
```

For push-style bodies call `capture.onBytes(...)`, then `capture.complete()` or
`capture.abandoned()`.

## Troubleshooting

1. **Nothing is logged.** Check `coolhand.getStats()`: `totalRequests` counts requests the
   integration saw, `interceptedCalls` those it captured. If `totalRequests` is 0 the client in use
   is not the monitored one. If only `interceptedCalls` is 0 the URL did not match a pattern, was
   excluded, or was the Coolhand API itself.
2. **Logs arrive late or not at all in a CLI or test.** Call `coolhand.flush(...)` before exit.
3. **A self-hosted gateway is not captured.** Add it with a custom `patternsFile`.
4. **Declared context missing.** The request was sent from another thread; see
   [declared-workloads.md](declared-workloads.md#threads).
5. **Prove it in CI** with `CoolhandTestUtils.assertInterceptionOccurred`.
6. **See what happens.** `silent(false)` logs each capture through `System.Logger`
   (`com.coolhandlabs.coolhand`); `debug(true)` adds payload sizes; `dryRun(true)` logs payloads
   instead of sending them.
