package com.coolhandlabs.coolhand;

/**
 * Why a request was recognised as an LLM call.
 *
 * @param pattern the provider pattern that matched
 * @param matchType {@code "domain"} or {@code "path"}
 * @param matchValue the domain or path entry that matched
 */
public record MatchedPattern(ApiPattern pattern, String matchType, String matchValue) {}
