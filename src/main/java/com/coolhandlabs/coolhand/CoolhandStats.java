package com.coolhandlabs.coolhand;

/**
 * Monitoring counters.
 *
 * @param totalRequests every request an integration bound to this client saw
 * @param interceptedCalls requests captured for logging
 * @param apiEndpoint where logs are sent
 */
public record CoolhandStats(long totalRequests, long interceptedCalls, String apiEndpoint) {}
