# Feedback Links

Link feedback to an optimization as evidence (private API key).

```java
Optional<OptimizationFeedbackLink> link = coolhand.linkFeedback("opt_1", "fb_1");
coolhand.linkFeedback("opt_1", "fb_2", "the user asked for shorter answers");   // a null note is sent as null

Optional<BulkLinkFeedbackResult> totals = coolhand.bulkLinkFeedback("opt_1", feedbackIds, "batch import");
totals.ifPresent(t -> System.out.println(t.linked() + " linked, " + t.notFound().size() + " not found"));

coolhand.unlinkFeedback("opt_1", link.orElseThrow().id());   // the link's id, not the feedback's
```

- `POST /api/v2/optimizations/:optimization_id/feedback_links` with `{"feedback_id": ...}` or
  `{"feedback_ids": [...]}` (and `note` when given); `DELETE .../feedback_links/:link_id` to unlink.
- `bulkLinkFeedback` sends batches of 100 ids, one after another, and merges `linked`,
  `already_linked`, `errored` and `not_found`. A batch that fails throws, and earlier batches stay
  linked.
- Blank ids, `.`/`..` and an empty id list throw `IllegalArgumentException` before any request.
- In dry-run mode nothing is sent and the result is empty.
- Failures throw `CoolhandHttpException` (with `status()`) or `CoolhandException`.
