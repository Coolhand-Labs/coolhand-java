# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-11-05

First release of the Coolhand Java SDK, at feature parity with coolhand-node 0.17.1 wherever the
feature applies to the JVM.

### ✨ New Features
- **`Coolhand` client** configured with `CoolhandOptions` (API key, `baseUrl`, `silent`, `debug`,
  `dryRun`, `requestTimeout`, `patternsFile`, `excludeApiPatterns`, `captureIdleTimeout`) or from the
  same environment variables coolhand-node reads (`COOLHAND_API_KEY`, `COOLHAND_SILENT`,
  `COOLHAND_DEBUG`, `COOLHAND_DRY_RUN`, `COOLHAND_BASE_URL`, `COOLHAND_PATTERNS_FILE`,
  `COOLHAND_EXCLUDE_API_PATTERNS`). A process-wide client (`Coolhand.global()`,
  `initializeGlobalMonitoring`) plays the part of coolhand-node's auto-monitor.
- **Interception integrations**: `CoolhandHttpClient` (`java.net.http.HttpClient`),
  `CoolhandInterceptor` (OkHttp), `CoolhandAnthropic` / `CoolhandAnthropicHttpClient` (Anthropic Java
  SDK 3.5.0+, including streaming) and `CoolhandOpenAI` / `CoolhandOpenAIHttpClient` (OpenAI Java SDK
  4.90.0+). Bodies are teed as the host reads them, capped at 50 MiB, decompressed for the log
  (gzip, deflate, and br when a Brotli decoder is present), and logged once complete; abandoned
  streams are logged with `stream_incomplete: true`.
- **The same wire format as coolhand-node**: `POST /api/v2/llm_request_logs` with
  `raw_request`/`collector`/`metadata`/`declared`, collector strings `coolhand-java-<version>-<method>`,
  the bundled `api-patterns.json`, default exclude patterns, self-endpoint and non-inference
  filtering, and header/URL/body credential redaction.
- **Declared workloads**: `Coolhand.withContext(DeclaredContext, block)` with coolhand-node's
  validation and nesting rules, thread-scoped, plus `wrapWithContext` for other threads.
- **Services** with synchronous and `CompletableFuture` variants: feedback (create, search, get),
  logs (submit, search, content), templates (search, get, upsert), step templates, workloads
  (search, upsert), referenced files, client files (upload, list, download, status), optimization
  feedback links, the help center (keyless) and MCP tool calls.
- **Test utilities**: `CoolhandTestUtils.captureInterceptionSnapshot` /
  `assertInterceptionOccurred`.
