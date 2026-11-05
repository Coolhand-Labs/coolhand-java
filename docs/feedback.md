# Feedback

## Creating feedback

```java
Optional<FeedbackResponse> created = coolhand.createFeedback(LlmRequestLogFeedback.builder()
        .llmProviderUniqueId("msg_01AbC")
        .sentiment(Sentiment.LIKE)
        .explanation("Exactly the format we needed")
        .creatorType(CreatorType.HUMAN)
        .creatorUniqueId("user-42")
        .build());
```

`POST /api/v2/llm_request_log_feedbacks` with:

```json
{"llm_request_log_feedback": {"sentiment": "like", "creator_type": "human", "creator_unique_id": "user-42",
  "explanation": "Exactly the format we needed", "llm_provider_unique_id": "msg_01AbC",
  "collector": "coolhand-java-0.1.0-manual"}}
```

Only the fields you set are sent, in this order:

| Builder method | Wire field | Meaning |
|---|---|---|
| `llmRequestLogId(long \| String)` | `llm_request_log_id` | The log's id (integer or hashid) |
| `like(boolean)` (deprecated) | `like` / `sentiment` | Converted to `sentiment` (`like`/`dislike`) when no sentiment is set |
| `sentiment(Sentiment)` | `sentiment` | `like`, `dislike` or `neutral` |
| `creatorType(CreatorType)` | `creator_type` | `human`, `agent` or `unknown` (server default) |
| `creatorUniqueId(String)` | `creator_unique_id` | Your id for who gave the feedback |
| `workloadHashid(String)` | `workload_hashid` | The workload it belongs to |
| `explanation(String)` | `explanation` | Why |
| `revisedOutput(String)` | `revised_output` | A corrected output |
| `llmProviderUniqueId(String)` | `llm_provider_unique_id` | The provider's response id |
| `originalOutput(String)` | `original_output` | The output text, for fuzzy matching |
| `clientUniqueId(String)` | `client_unique_id` | Your own id for the request |

The SDK's collector string is always appended.

### Matching feedback to a log

Give at least one of: `llmRequestLogId` (exact), `llmProviderUniqueId` (the provider's id for the
response, e.g. Anthropic's `msg_...` or OpenAI's `chatcmpl-...`; the most practical key since you
already have it), `clientUniqueId`, or `originalOutput` (fuzzy match on the output text).

### Result and errors

The result is the created record: `id`, `llmRequestLogId` (the matched log's hashid, `null` when
none matched yet), `sentiment`, `creatorType`, `workloadId`, `warnings` and the rest, with the full
JSON in `raw()`.

`createFeedback` never throws for API or network failures: a non-2xx response, a timeout, a body
that is not JSON, or a dry run all give `Optional.empty()` (the failure is logged at `ERROR`, the
API key masked). `createFeedbackAsync` returns a `CompletableFuture` that always completes normally
with the same `Optional`.

## Searching feedback (private key)

```java
Page<JsonNode> page = coolhand.searchFeedback(SearchFeedbackParams.builder()
        .sentimentEq(0)                       // 0 dislike, 1 neutral, 2 like
        .explanationCont("too long")
        .predicate("created_at_gteq", "2026-10-01")
        .sort("created_at desc")
        .page(1).per(50)
        .build());
```

Each predicate is sent as `q[<name>]=<value>` (Ransack), `page` and `per` as top-level parameters.
Items are the `:summary` view (no `original_output`/`revised_output`); `pagination()` is read from
the response body's `pagination` envelope.

## Getting one record (private key)

```java
JsonNode feedback = coolhand.getFeedback("fb_hashid");   // includes original_output, revised_output, feedback_partials
```

Blank ids and `.`/`..` throw `IllegalArgumentException`; an unknown id throws
`CoolhandHttpException` with `status()` 404.
