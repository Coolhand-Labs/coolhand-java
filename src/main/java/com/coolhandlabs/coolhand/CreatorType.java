package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Who supplied a piece of feedback; the server defaults to {@code unknown} when omitted. */
public enum CreatorType {
    HUMAN, AGENT, UNKNOWN;

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The constant for a wire value, or {@code null} for anything else. */
    public static CreatorType fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (CreatorType c : values()) {
            if (c.wireName().equals(value)) {
                return c;
            }
        }
        return null;
    }
}
