package com.coolhandlabs.coolhand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CollectorTest {
    @Test
    void versionComesFromThePom() {
        assertEquals("coolhand-java", Version.PACKAGE_NAME);
        assertTrue(Version.PACKAGE_VERSION.matches("\\d+\\.\\d+\\.\\d+.*"), Version.PACKAGE_VERSION);
        assertEquals("coolhand-java-" + Version.PACKAGE_VERSION, Version.PACKAGE_IDENTIFIER);
    }

    @Test
    void collectorStringMatchesNodeFormat() {
        String base = "coolhand-java-" + Version.PACKAGE_VERSION;
        assertEquals(base, CollectionMethod.collectorString(null));
        assertEquals(base + "-manual", CollectionMethod.collectorString(CollectionMethod.MANUAL));
        assertEquals(base + "-global-monitoring", CollectionMethod.collectorString(CollectionMethod.GLOBAL_MONITORING));
        assertEquals(base + "-auto-monitor", CollectionMethod.collectorString(CollectionMethod.AUTO_MONITOR));
    }

    @Test
    void userAgentNamesTheSdkAndVersion() {
        assertEquals("coolhand-java/" + Version.PACKAGE_VERSION, Version.userAgent());
    }
}
