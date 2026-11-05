package com.coolhandlabs.coolhand;

/** How a log or feedback reached Coolhand; the suffix of the collector string. */
public enum CollectionMethod {
    /** Captured by one of the interception integrations. */
    GLOBAL_MONITORING("global-monitoring"),
    /** Submitted through an explicit API call ({@code logRequest}, {@code createFeedback}). */
    MANUAL("manual"),
    /** Captured by monitoring started from environment variables alone. */
    AUTO_MONITOR("auto-monitor");

    private final String wireName;

    CollectionMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * The collector identification string, {@code coolhand-java-<version>-<method>}, or
     * {@code coolhand-java-<version>} when {@code method} is {@code null} (coolhand-node's
     * {@code getCollectorString}).
     */
    public static String collectorString(CollectionMethod method) {
        return method == null ? Version.PACKAGE_IDENTIFIER : Version.PACKAGE_IDENTIFIER + "-" + method.wireName;
    }
}
