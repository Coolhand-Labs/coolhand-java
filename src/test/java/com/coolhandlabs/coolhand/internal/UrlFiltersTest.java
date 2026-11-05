package com.coolhandlabs.coolhand.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UrlFiltersTest {
    @Test
    void excludeEntriesAreSubstringsNotGlobs() {
        List<String> patterns = List.of("/internal-*", "internal.corp");
        assertTrue(UrlFilters.matchesExcludePattern("https://internal.corp/v1/chat", patterns));
        assertFalse(UrlFilters.matchesExcludePattern("https://api.openai.com/internal-x", patterns));
        assertTrue(UrlFilters.matchesExcludePattern("https://api.openai.com/internal-*", patterns));
    }

    @Test
    void excludeMatchesTheCanonicalHostToo() {
        List<String> patterns = List.of("internal.corp");
        assertTrue(UrlFilters.matchesExcludePattern("https://Internal.Corp/v1", patterns));
        assertTrue(UrlFilters.matchesExcludePattern("https://internal.corp./v1", patterns));
        assertTrue(UrlFilters.matchesExcludePattern("https://example.com", List.of("example.com/")));
        assertTrue(UrlFilters.matchesExcludePattern("https://x.example/Path", List.of("/Path")));
        assertFalse(UrlFilters.matchesExcludePattern("https://x.example/Path", List.of("/path")));
    }

    @Test
    void emptyAndNullEntriesNeverMatchEverything() {
        assertFalse(UrlFilters.matchesExcludePattern("https://api.openai.com/v1", Arrays.asList("", null)));
        assertFalse(UrlFilters.matchesExcludePattern("https://api.openai.com/v1", List.of()));
    }

    @Test
    void defaultExcludesCoverAzureControlPlaneSpellings() {
        List<String> defaults = com.coolhandlabs.coolhand.Coolhand.DEFAULT_EXCLUDE_API_PATTERNS;
        assertTrue(UrlFilters.matchesExcludePattern("https://r.openai.azure.com/openai/v1/files", defaults));
        assertTrue(UrlFilters.matchesExcludePattern("https://r.openai.azure.com/openai/batches?x=1", defaults));
        assertFalse(UrlFilters.matchesExcludePattern("https://r.openai.azure.com/openai/deployments/d/chat", defaults));
    }

    @Test
    void selfEndpointMatchesHostAndEffectivePort() {
        UrlFilters.SelfEndpoint self = UrlFilters.computeSelfEndpoint("https://coolhandlabs.com/api/v2/llm_request_logs");
        assertEquals(new UrlFilters.SelfEndpoint("coolhandlabs.com", "443"), self);
        assertTrue(UrlFilters.isSelfEndpointUrl("https://CoolhandLabs.com./anything", self));
        assertTrue(UrlFilters.isSelfEndpointUrl("https://coolhandlabs.com:443/x", self));
        assertFalse(UrlFilters.isSelfEndpointUrl("http://coolhandlabs.com/x", self));
        UrlFilters.SelfEndpoint local = UrlFilters.computeSelfEndpoint("http://localhost:3000/api");
        assertTrue(UrlFilters.isSelfEndpointUrl("http://localhost:3000/v1/chat", local));
        assertFalse(UrlFilters.isSelfEndpointUrl("http://localhost:11434/api/chat", local));
        assertNull(UrlFilters.computeSelfEndpoint("not a url"));
    }

    @Test
    void nonInferenceAnthropicEndpointsAreSkipped() {
        assertTrue(UrlFilters.isNonInferenceUrl("https://api.anthropic.com/api/event_logging/batch", "POST"));
        assertTrue(UrlFilters.isNonInferenceUrl("https://api.anthropic.com/api/directory/servers?limit=5", "get"));
        assertTrue(UrlFilters.isNonInferenceUrl("https://api.anthropic.com/v1/environments/env_1/work/poll", "GET"));
        assertFalse(UrlFilters.isNonInferenceUrl("https://api.anthropic.com/api/directory/servers", "POST"));
        assertFalse(UrlFilters.isNonInferenceUrl("https://api.anthropic.com/v1/messages", "POST"));
        assertFalse(UrlFilters.isNonInferenceUrl("https://other.example/api/event_logging/x", "POST"));
    }
}
