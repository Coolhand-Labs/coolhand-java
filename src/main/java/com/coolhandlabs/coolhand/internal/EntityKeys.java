package com.coolhandlabs.coolhand.internal;

/**
 * Client-side mirror of the server's workload/template key and description rules. A violation
 * server-side drops the whole log, so these are checked before anything is sent.
 */
public final class EntityKeys {
    public static final int MAX_KEY_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;

    private EntityKeys() {}

    /** Why {@code key} is not an acceptable workload/template key, or {@code null} if it is. */
    public static String keyProblem(String key) {
        if (key.isEmpty() || JsText.trim(key).isEmpty()) {
            return "must not be blank";
        }
        if (JsText.codePointLength(key) > MAX_KEY_LENGTH) {
            return "must be at most " + MAX_KEY_LENGTH + " characters";
        }
        if (hasLoneSurrogate(key)) {
            return "must be valid Unicode (contains a lone surrogate)";
        }
        if (hasInvisible(key)) {
            return "must not contain control or invisible characters";
        }
        if (!key.equals(JsText.trim(key))) {
            return "must not have leading or trailing whitespace";
        }
        if (key.startsWith("/") || key.endsWith("/")) {
            return "must not start or end with \"/\"";
        }
        if (key.contains("//")) {
            return "must not contain \"//\"";
        }
        return null;
    }

    /** Why {@code description} is unacceptable, or {@code null}. Length is counted in code points. */
    public static String descriptionProblem(String description) {
        return JsText.codePointLength(description) > MAX_DESCRIPTION_LENGTH
                ? "must be at most " + MAX_DESCRIPTION_LENGTH + " characters"
                : null;
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return true;
            }
            if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    // \p{Cc}, \p{Cf} and the line/paragraph separators.
    private static boolean hasInvisible(String s) {
        return s.codePoints().anyMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.CONTROL || type == Character.FORMAT || cp == 0x2028 || cp == 0x2029;
        });
    }
}
