package com.coolhandlabs.coolhand.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** coolhand-node's {@code parseBody}: JSON documents become trees, anything else stays text. */
public final class BodyParser {
    private BodyParser() {}

    /**
     * {@code null} for empty input; the parsed document for JSON (a top-level array is re-joined as
     * newline-delimited JSON text, one element per line); otherwise the text itself.
     */
    public static JsonNode parseBody(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            JsonNode parsed = Json.parse(text);
            if (parsed == null || parsed.isMissingNode()) {
                return TextNode.valueOf(text);
            }
            if (parsed.isArray()) {
                List<String> lines = new ArrayList<>(parsed.size());
                for (JsonNode item : parsed) {
                    lines.add(Json.stringify(item));
                }
                return TextNode.valueOf(String.join("\n", lines));
            }
            return parsed;
        } catch (JsonProcessingException | RuntimeException e) {
            return TextNode.valueOf(text);
        }
    }

    /** Parses then sanitizes; fails closed (returns {@code null}) if either step throws. */
    public static JsonNode parseAndSanitize(String text, UnaryOperator<JsonNode> sanitize, Consumer<Throwable> onError) {
        try {
            JsonNode parsed = parseBody(text);
            return sanitize == null ? parsed : sanitize.apply(parsed);
        } catch (RuntimeException e) {
            if (onError != null) {
                onError.accept(e);
            }
            return null;
        }
    }
}
