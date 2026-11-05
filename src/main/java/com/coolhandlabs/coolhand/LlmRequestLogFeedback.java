package com.coolhandlabs.coolhand;

/**
 * Feedback on an LLM output, the body of {@link Coolhand#createFeedback(LlmRequestLogFeedback)}.
 * Every field is optional; give at least one way to match the log: {@code llmRequestLogId},
 * {@code llmProviderUniqueId} (e.g. the provider's response id), {@code originalOutput} (fuzzy
 * match) or {@code clientUniqueId}. See {@code docs/feedback.md}.
 *
 * @param llmRequestLogId the log's id: a {@code Long} or a hashid {@code String}
 * @param like deprecated boolean form; converted to {@code sentiment} when that is absent
 * @param sentiment like, dislike or neutral
 * @param creatorType human, agent or unknown
 * @param creatorUniqueId your id for whoever gave the feedback
 * @param workloadHashid the workload this feedback belongs to
 * @param explanation why
 * @param revisedOutput a corrected output
 * @param llmProviderUniqueId the provider's id for the response (e.g. Anthropic's {@code msg_...})
 * @param originalOutput the output being rated, for fuzzy matching
 * @param clientUniqueId your own id for the request
 */
public record LlmRequestLogFeedback(
        Object llmRequestLogId,
        Boolean like,
        Sentiment sentiment,
        CreatorType creatorType,
        String creatorUniqueId,
        String workloadHashid,
        String explanation,
        String revisedOutput,
        String llmProviderUniqueId,
        String originalOutput,
        String clientUniqueId) {

    public LlmRequestLogFeedback {
        if (llmRequestLogId != null && !(llmRequestLogId instanceof Number) && !(llmRequestLogId instanceof String)) {
            throw new IllegalArgumentException("llmRequestLogId must be a Number or a String");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link LlmRequestLogFeedback}. */
    public static final class Builder {
        private Object llmRequestLogId;
        private Boolean like;
        private Sentiment sentiment;
        private CreatorType creatorType;
        private String creatorUniqueId;
        private String workloadHashid;
        private String explanation;
        private String revisedOutput;
        private String llmProviderUniqueId;
        private String originalOutput;
        private String clientUniqueId;

        private Builder() {}

        public Builder llmRequestLogId(long id) { this.llmRequestLogId = id; return this; }
        public Builder llmRequestLogId(String hashid) { this.llmRequestLogId = hashid; return this; }
        /** @deprecated use {@link #sentiment(Sentiment)} */
        @Deprecated
        public Builder like(boolean like) { this.like = like; return this; }
        public Builder sentiment(Sentiment sentiment) { this.sentiment = sentiment; return this; }
        public Builder creatorType(CreatorType creatorType) { this.creatorType = creatorType; return this; }
        public Builder creatorUniqueId(String value) { this.creatorUniqueId = value; return this; }
        public Builder workloadHashid(String value) { this.workloadHashid = value; return this; }
        public Builder explanation(String value) { this.explanation = value; return this; }
        public Builder revisedOutput(String value) { this.revisedOutput = value; return this; }
        public Builder llmProviderUniqueId(String value) { this.llmProviderUniqueId = value; return this; }
        public Builder originalOutput(String value) { this.originalOutput = value; return this; }
        public Builder clientUniqueId(String value) { this.clientUniqueId = value; return this; }

        public LlmRequestLogFeedback build() {
            return new LlmRequestLogFeedback(llmRequestLogId, like, sentiment, creatorType, creatorUniqueId,
                    workloadHashid, explanation, revisedOutput, llmProviderUniqueId, originalOutput, clientUniqueId);
        }
    }
}
