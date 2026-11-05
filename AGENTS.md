# coolhand-java

## Setup

Java 17+ and Maven 3.9+. There is no separate install step: Maven resolves dependencies on the
first build. Without a local JDK, use Docker:

```bash
docker run --rm -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-17 mvn -B verify
```

## Verify before committing

```bash
mvn -B verify
```

This is the single gate, and exactly what CI runs (`.github/workflows/ci.yml`, on Java 17, 21 and
25). It compiles with `-Xlint:all` for a Java 17 target, runs every test and builds the sources and
javadoc jars (javadoc errors fail the build). A green run means a green CI run. Keep the build free
of compiler and javadoc warnings.

The tests never touch the network: `support/FakeServer` (a `com.sun.net.httpserver.HttpServer`)
stands in for both the LLM provider and the Coolhand API, and integration tests assert the JSON
actually posted.

## Pull requests

If a PR addresses one or more GitHub issues, prefix the PR title with a `[closes #N]` tag per issue,
e.g. `[closes #11] [closes #15] fix for headers`.

## Changelog and versioning

Do not add `CHANGELOG.md` entries or bump the `pom.xml` version on feature/fix branches or in PRs.
Both are written once, at release time, from the set of merged PRs. The version lives only in
`pom.xml`; the build writes it into `coolhand-version.properties`, which drives the collector string
and the User-Agent.

## Code conventions

**Java:**
- Java 17 language level, no preview features. Anything newer (e.g. `HttpClient.close()` from Java
  21) is reached reflectively or through methods that compile for 17.
- Public API: immutable records or final classes with builders, `Optional` for "may be absent",
  `CompletableFuture` for async variants. Unchecked exceptions only: `IllegalArgumentException` for
  bad input (thrown before any request), `CoolhandException` / `CoolhandHttpException` for API
  failures. Submission methods (`logRequest`, `createFeedback`, uploads) never throw for API or
  network failures; they return `Optional.empty()`, as coolhand-node resolves to `null`.
- Capture must never break the host's request: anything the integrations do around the host call
  is guarded and degrades to a pass-through.
- `internal` packages are not API; everything else is.
- No new runtime dependencies beyond Jackson. Integrations depend on their library as an
  `optional` Maven dependency.

**DRY:**
- Before adding a service method, check `BaseService` for an existing helper (request building,
  query strings, error shape, JSON/pagination parsing) rather than re-implementing it.
- Integrations stay thin adapters over `interception` (`RequestMonitor`, `CaptureSession`,
  `ResponseCapture`); capture logic lives there once.

## README and docs philosophy

The README is a landing page: install, quick start, what it supports, where to go next. Anything
needing more than one code block goes in `docs/`. Each read API has its own `docs/*.md`.

## Cross-SDK alignment

coolhand-node is the reference implementation for the Coolhand SDK family. Wire format (endpoints,
payload field names, collector strings), provider patterns (`api-patterns.json` is copied verbatim)
and redaction rules follow it. When porting a coolhand-node change, port its tests too, and record
any deliberate difference in the README's "Differences from coolhand-node" section.
