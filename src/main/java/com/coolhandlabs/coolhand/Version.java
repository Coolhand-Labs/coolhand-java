package com.coolhandlabs.coolhand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This SDK's name and version, read from the build-filtered {@code coolhand-version.properties} so
 * {@code pom.xml}'s version is the single source for the collector string and the User-Agent.
 */
public final class Version {
    /** {@code coolhand-java}. */
    public static final String PACKAGE_NAME;
    /** The released version, e.g. {@code 0.1.0}. */
    public static final String PACKAGE_VERSION;
    /** {@code coolhand-java-<version>}. */
    public static final String PACKAGE_IDENTIFIER;

    static {
        Properties props = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("coolhand-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall through to the defaults below
        }
        PACKAGE_NAME = props.getProperty("name", "coolhand-java");
        String version = props.getProperty("version", "unknown");
        PACKAGE_VERSION = version.startsWith("${") ? "unknown" : version;
        PACKAGE_IDENTIFIER = PACKAGE_NAME + "-" + PACKAGE_VERSION;
    }

    private Version() {}

    /** The {@code User-Agent} sent on every call to the Coolhand API: {@code coolhand-java/<version>}. */
    public static String userAgent() {
        return PACKAGE_NAME + "/" + PACKAGE_VERSION;
    }
}
