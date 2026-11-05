package com.coolhandlabs.coolhand.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.Coolhand;
import com.coolhandlabs.coolhand.CoolhandOptions;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoolhandTestUtilsTest {
    @AfterEach
    void reset() {
        Coolhand.resetGlobalMonitoring();
    }

    @Test
    void passesWhenAnInterceptionHappenedAndFailsOtherwise() {
        Coolhand c = new Coolhand(CoolhandOptions.builder().apiKey("k").dryRun(true).build());
        InterceptionSnapshot before = CoolhandTestUtils.captureInterceptionSnapshot(c);
        AssertionError none = assertThrows(AssertionError.class, () -> CoolhandTestUtils.assertInterceptionOccurred(c, before));
        assertTrue(none.getMessage().contains("interceptedCalls did not increase (before: 0, after: 0)"));
        assertEquals("custom", assertThrows(AssertionError.class,
                () -> CoolhandTestUtils.assertInterceptionOccurred(c, before, "custom")).getMessage());

        c.monitor().begin("POST", "https://api.openai.com/v1/chat/completions", Map.of());
        CoolhandTestUtils.assertInterceptionOccurred(c, before);
    }

    @Test
    void readsTheGlobalClient() {
        InterceptionSnapshot before = CoolhandTestUtils.captureInterceptionSnapshot();
        assertEquals(0, before.interceptedCalls());
        Coolhand global = Coolhand.initializeGlobalMonitoring(CoolhandOptions.builder().apiKey("k").dryRun(true).build());
        global.monitor().begin("POST", "https://api.anthropic.com/v1/messages", Map.of());
        CoolhandTestUtils.assertInterceptionOccurred(before);
    }
}
