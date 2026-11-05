package com.coolhandlabs.coolhand.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;

/**
 * The one {@link ObjectMapper} this library uses. Parsing is strict like {@code JSON.parse}: one
 * document, trailing content is an error (so NDJSON is not silently cut to its first line), and the
 * string/nesting limits are raised so a large captured body parses rather than falling back to text.
 */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    static {
        MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(Integer.MAX_VALUE)
                .maxNestingDepth(5_000)
                .maxNumberLength(100_000)
                .build());
    }

    private Json() {}

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Parses {@code text} as exactly one JSON document. */
    public static JsonNode parse(String text) throws JsonProcessingException {
        return MAPPER.readTree(text);
    }

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** Text of a field, or {@code null} when it is absent or JSON null. Numbers become their text. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    public static Boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isBoolean() ? null : value.booleanValue();
    }
}
