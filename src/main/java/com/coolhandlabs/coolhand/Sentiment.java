package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Feedback sentiment, sent as {@code "like"}, {@code "dislike"} or {@code "neutral"}. */
public enum Sentiment {
    LIKE, DISLIKE, NEUTRAL;

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The constant for a wire value, or {@code null} for anything else. */
    public static Sentiment fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (Sentiment s : values()) {
            if (s.wireName().equals(value)) {
                return s;
            }
        }
        return null;
    }
}
