package com.coolhandlabs.coolhand.testing;

/**
 * The interception counter at one moment, for {@link CoolhandTestUtils#assertInterceptionOccurred}.
 *
 * @param interceptedCalls the client's {@code interceptedCalls} when the snapshot was taken
 */
public record InterceptionSnapshot(long interceptedCalls) {}
