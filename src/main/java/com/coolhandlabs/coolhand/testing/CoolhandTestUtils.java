package com.coolhandlabs.coolhand.testing;

import com.coolhandlabs.coolhand.Coolhand;

/**
 * Helpers for your own tests: prove that a code path's LLM calls are really monitored (coolhand-node's
 * {@code coolhand-node/test-utils}).
 *
 * <pre>{@code
 * InterceptionSnapshot before = CoolhandTestUtils.captureInterceptionSnapshot(coolhand);
 * myService.summarise("...");
 * CoolhandTestUtils.assertInterceptionOccurred(coolhand, before);
 * }</pre>
 *
 * <p>The counter is per client and not scoped to a request: a delta proves some matched request was
 * intercepted in the window, not that a particular concurrent one was. A failure means the call did
 * not go through a monitored client (an HTTP client built without the Coolhand integration), did not
 * match a provider pattern, was excluded, or was a non-inference call.
 */
public final class CoolhandTestUtils {
    private CoolhandTestUtils() {}

    /** Snapshot of the global client's counter (zero when there is no global client). */
    public static InterceptionSnapshot captureInterceptionSnapshot() {
        return new InterceptionSnapshot(Coolhand.getGlobalStats().interceptedCalls());
    }

    /** Snapshot of {@code coolhand}'s counter. */
    public static InterceptionSnapshot captureInterceptionSnapshot(Coolhand coolhand) {
        return new InterceptionSnapshot(coolhand.getStats().interceptedCalls());
    }

    /**
     * Asserts the global client intercepted at least one request since {@code before}.
     *
     * @throws AssertionError if it did not
     */
    public static void assertInterceptionOccurred(InterceptionSnapshot before) {
        check(before, captureInterceptionSnapshot(), null);
    }

    public static void assertInterceptionOccurred(InterceptionSnapshot before, String message) {
        check(before, captureInterceptionSnapshot(), message);
    }

    /** Asserts {@code coolhand} intercepted at least one request since {@code before}. */
    public static void assertInterceptionOccurred(Coolhand coolhand, InterceptionSnapshot before) {
        check(before, captureInterceptionSnapshot(coolhand), null);
    }

    public static void assertInterceptionOccurred(Coolhand coolhand, InterceptionSnapshot before, String message) {
        check(before, captureInterceptionSnapshot(coolhand), message);
    }

    private static void check(InterceptionSnapshot before, InterceptionSnapshot after, String message) {
        if (after.interceptedCalls() <= before.interceptedCalls()) {
            throw new AssertionError(message != null ? message
                    : "Expected coolhand-java to intercept at least one new request, but interceptedCalls did not "
                    + "increase (before: " + before.interceptedCalls() + ", after: " + after.interceptedCalls()
                    + "). This can mean the request was sent through an HTTP client without the Coolhand integration, "
                    + "or that it didn't match a configured API pattern (or matched an excluded/self pattern, or was a "
                    + "non-inference call) — see https://github.com/Coolhand-Labs/coolhand-java/blob/main/docs/"
                    + "global-monitoring.md troubleshooting.");
        }
    }
}
