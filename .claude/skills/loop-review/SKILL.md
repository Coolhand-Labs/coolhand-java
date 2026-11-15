---
name: loop-review
description: |
  Iteratively runs code review against the current diff, applies fixes, and
  re-reviews until a round comes back clean (or a safety cap is hit). Use
  when the user types /loop-review, asks to "loop the review", "review
  until clean", "keep reviewing and fixing until nothing's left", or wants
  a self-healing code review cycle instead of a single one-shot pass.
user_invocable: true
allowed-tools: Edit, Write, Read, Bash, Agent, Skill, Glob, Grep
argument-hint: [low|medium|high|max]
version: 0.1.0
---

Run an automated code review + fix loop on the current branch. Keep iterating until the reviewer reports no issues.

## Setup

- Effort level: `$ARGUMENTS` (default: `high` if blank)
- Max iterations: 5
- Review scope: `git diff $(git merge-base origin/main HEAD)` — diffs from the branch point to the current working tree, so it reflects each iteration's uncommitted Step 3 fixes (a plain `origin/main...HEAD` three-dot diff would compare committed HEAD only and miss them, since Step 3 never commits)

## Loop Instructions

Repeat the following cycle up to 5 times:

### Step 1 — Review (Agent)

Before spawning, record `date +%s` as this iteration's start time — it brackets the `clock_seconds` CSV field together with the timestamp taken after Step 3's `mvn -B verify` (or after the LGTM determination in Step 2, for the terminal pass).

Spawn an Agent using the Agent tool with `thinking` set to the EFFORT value from Setup (default `high`) and this prompt (substitute ITERATION_NUM, EFFORT, and PREVIOUS_ADDRESSED):

---
You are a code reviewer doing pass ITERATION_NUM of an automated review loop on `coolhand-java`, a Java SDK published to Maven Central.

Run `git diff $(git merge-base origin/main HEAD)` to get the current branch diff, including any uncommitted fixes from prior iterations. Review it for:
- Correctness bugs and logic errors
- Security vulnerabilities introduced by this diff
- Missing/broken error handling
- Inefficiencies or unnecessary complexity
- Violations of project conventions in AGENTS.md (Java 17 language level and no preview features, unchecked exceptions only, submission methods never throwing for API/network failures, no new runtime dependency beyond Jackson, `BaseService` helpers reused instead of re-implemented, `internal` packages not treated as API, `mvn -B verify` as the only gate, README/docs split rules)
- DRY violations and non-semantic naming — flag duplicated logic that should reuse an existing helper, and identifiers whose names don't convey intent
- Test coverage — new or changed behavior must have tests that actually assert on meaningful outcomes (not just "doesn't raise"); flag missing edge cases (error paths, empty/absent inputs, `Optional.empty()` returns, pagination boundaries); tests must run offline against `support/FakeServer`, never the network
- Public API stability — does this diff change anything outside an `internal` package (public classes, method signatures, record components, builder methods)? If so, is the break necessary and called out in the PR description? Do not ask for a CHANGELOG entry or a `pom.xml` version bump: this repo writes both once, at release time, from the merged PRs.
- Capture safety — anything the integrations do around the host's request must be guarded and degrade to a pass-through; flag any path where an exception could escape into the host call.

Effort: EFFORT

Already addressed in prior iterations (fixed, or rejected with a stated reason) — do NOT re-flag these:
PREVIOUS_ADDRESSED

Tag every finding with one of these severities:
- `[CRITICAL]` — security vulnerabilities, wrong/broken behavior, performance problems (a diff that edits `CHANGELOG.md` or `pom.xml` is `[CRITICAL]`: `AGENTS.md` says both are written once, at release time)
- `[NICE-TO-HAVE]` — DRY violations, missing test coverage, code-reuse opportunities
- `[NITPICK]` — documentation, comments, naming, formatting-adjacent issues

Return a numbered list of issues with file path and line numbers, each prefixed with its severity tag, e.g. `1. [CRITICAL] file:line — problem — fix`. Be specific about what to fix and why.
If there are NO issues, the first line of your response must be exactly: LGTM: No issues found.
Always end your response with a line: `TOKENS_USED: <number>` — your best estimate of tokens used this pass (approximate is fine, this isn't metered). Include this line even in the LGTM case.
---

### Step 2 — Check Result

- If the first line of the agent's response is exactly `LGTM: No issues found.` → this is the terminal iteration. Immediately mark its `clock_seconds` end time (no `mvn -B verify` runs for this pass, since there's nothing to fix), record a minimal `=== Iteration N === / Reviewer found 0 issues.` block in the running Iteration Log (see format below), append its CSV row (see CSV Run Log format) with zero found/addressed/rejected counts and `tokens_used_approx` taken from the agent's trailing `TOKENS_USED` line, then go to Final Summary.
- If iteration count has reached 5 → exit the loop after Step 4 for this iteration, go to Final Summary (partial)
- Otherwise → proceed to Step 3

### Step 3 — Fix

For every finding the reviewer raised, either fix it or reject it with a one-line reason (false positive / out of scope / disagree with the call) — every finding must get one of these two dispositions. Use Edit, Write, and Bash tools to apply fixes directly. Do not add `CHANGELOG.md` entries or bump the `pom.xml` version for a breaking change — call it out in your summary instead, since both are written at release time.

Track, per iteration, the fixed count and the rejected count broken down by severity — this breakdown feeds the narrative Iteration Log (see format below); the CSV Run Log only needs the flat totals. Append each finding's disposition (fixed, or rejected with its reason) to the running `PREVIOUS_ADDRESSED` list that feeds the next iteration's reviewer prompt, so rejected findings aren't re-flagged and don't cause the loop to churn.

Run `mvn -B verify` (compile with -Xlint:all, run every test, build sources and javadoc jars; never invoke the compiler or javadoc individually) before logging the iteration. If it fails, fix the failure before moving on.

### Step 4 — Log & Continue

Record this iteration in your running log (see format below) and append its CSV row now (see CSV Run Log — each iteration writes its own row as soon as it completes, not in a batch at the end), then go back to Step 1 with the next iteration number.

## Iteration Log Format

Maintain this log as you work:

```
=== Iteration 1 ===
Reviewer found T issues (C critical, H nice-to-have, K nitpick):
  1. [CRITICAL] [file:line] description
  2. ...
Fixed: X (2 critical, 1 nitpick)
  - Applied: [description of fix]
Rejected: Y (1 nice-to-have)
  - Rejected: [description] — [reason]
mvn -B verify: PASS | FAIL (details)

=== Iteration 2 ===
...

=== RESULT ===
[CLEAN after N iterations] or [STOPPED at max iterations — N issues remain]
```

## CSV Run Log

Append one row per iteration to `~/loop-review-outputs/coolhand-java.csv` as soon as that iteration completes (Step 4 for a fix iteration, or immediately at Step 2 for the terminal LGTM pass) — do not batch all rows at the end, so completed iterations are still recorded if the loop is interrupted before finishing. If the directory or file doesn't exist, create them first with this header:

```
timestamp,branch,iteration,model,thinking_level,clock_seconds,tokens_used_approx,critical_found,nice_to_have_found,nitpick_found,total_found,issues_addressed,issues_rejected
```

For each iteration's row:
- `timestamp` — `date -u +%Y-%m-%dT%H:%M:%SZ` at write time
- `branch` — `git branch --show-current`
- `iteration` — the loop iteration number (1-indexed)
- `model` — `default`
- `thinking_level` — the EFFORT value used that iteration
- `clock_seconds` — wall-clock elapsed, bracketed with `date +%s` taken right before spawning that iteration's Step-1 agent and right after that iteration's Step-3 `mvn -B verify` completes (for the terminal LGTM pass, which skips Step 3, bracket the end time right after the LGTM determination in Step 2 instead)
- `tokens_used_approx` — the `TOKENS_USED` value the reviewer reported that iteration
- `critical_found` / `nice_to_have_found` / `nitpick_found` / `total_found` — counts from Step 1
- `issues_addressed` — fixed count from Step 3
- `issues_rejected` — rejected count from Step 3

Write each row with a quoted heredoc (`cat >> ~/loop-review-outputs/coolhand-java.csv <<'EOF' ... EOF`), not an unquoted one — an unquoted heredoc lets `$(...)` and backtick command substitution in any field execute as shell commands at write time. `tokens_used_approx` is copied from reviewer-agent output influenced by arbitrary diff content, and `branch` comes from `git branch --show-current`; neither is trusted input. Before writing, validate `tokens_used_approx` is a bare integer (re-derive/omit it otherwise) and strip or reject commas from `branch` — commas would break CSV column alignment. Also strip backticks/`$(` from `branch` as defense-in-depth, in case this is ever rewritten without a quoted heredoc; with the quoted heredoc above they're already inert.

## Final Summary

After the loop exits and the CSV Run Log has been written, output:

1. **Overall result**: CLEAN (N iterations) or STOPPED (issues remain)
2. **Per-iteration breakdown**: What was found (by severity) vs. what was fixed/rejected each round
3. **All files modified**: Complete list of files touched across all iterations
4. **Public API changes**: Any change to the public surface, and how it was justified (no CHANGELOG/version edits belong in a PR)
5. **Remaining issues** (if stopped at max): Unresolved items with context on why they're hard to fix automatically
6. **CSV log**: Number of rows appended and the path (`~/loop-review-outputs/coolhand-java.csv`)
