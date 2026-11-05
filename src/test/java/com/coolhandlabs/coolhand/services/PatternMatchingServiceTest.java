package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.coolhandlabs.coolhand.MatchedPattern;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PatternMatchingServiceTest {
    private final PatternMatchingService service = new PatternMatchingService(true);

    private String name(String url) {
        MatchedPattern m = service.matchesApiPatternFromUrl(url);
        return m == null ? null : m.pattern().name();
    }

    @Test
    void bundlesTheSamePatternsAsCoolhandNode() throws IOException {
        assertEquals(21, service.getPatternsCount());
        try (InputStream in = getClass().getResourceAsStream("/com/coolhandlabs/coolhand/api-patterns.json")) {
            assertNotNull(in);
            String text = new String(in.readAllBytes());
            // coolhand-node 0.17.1 src/api-patterns.json, byte for byte (checked against its first entry here)
            assertEquals(true, text.startsWith("{\n  \"patterns\": [\n    {\n      \"name\": \"OpenAI\","));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "https://api.openai.com/v1/chat/completions, OpenAI",
            "https://API.OpenAI.com/v1/chat/completions, OpenAI",
            "https://api.openai.com./v1/chat/completions, OpenAI",
            "https://eu.api.openai.com/v1/responses, OpenAI",
            "https://api.anthropic.com/v1/messages, Anthropic",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent, Google AI",
            "https://aiplatform.googleapis.com/v1/projects/p/locations/l/x:predict, Vertex AI",
            "https://openrouter.ai/api/v1/chat/completions, OpenRouter",
            "https://myres.openai.azure.com/openai/deployments/x/chat/completions, Azure OpenAI",
            "https://bedrock-runtime.us-east-1.amazonaws.com/model/claude/invoke, Bedrock",
            "https://bedrock-runtime-fips.us-east-1.api.aws/model/x/converse, Bedrock",
            "http://localhost:11434/api/chat, Ollama",
            "http://10.0.0.5:11434/api/generate, Ollama",
            "https://api.cohere.com/v2/chat, Cohere",
    })
    void matchesProviders(String url, String expected) {
        assertEquals(expected, name(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://notopenai.com/v1/chat/completions",
            "https://evil.com/api.openai.com/v1/chat",
            "https://openai.com/v1/chat/completions",
            "https://auth.openai.com/oauth/token",
            "not a url",
            "https://example.com/v1/chat/completions",
            "http://localhost:11434/api/tags", // Ollama port, but not an inference path
            "http://localhost:3000/api/chat", // Ollama path, but not its port or domain
            "https://bedrock.us-east-1.amazonaws.com/model/x", // control plane, not bedrock-runtime
            "https://bedrock-runtime.us-east-1.amazonaws.com/guardrail/x", // requiresPathMatch
            "https://api.cohere.com/v1/embed-jobs", // a path entry matches on a segment boundary only
    })
    void doesNotMatchOthers(String url) {
        assertNull(name(url));
    }

    @Test
    void requiresPathMatchResolvesDotSegmentsAndDuplicateSlashes() {
        assertEquals("Bedrock", name("https://bedrock-runtime.us-east-1.amazonaws.com/x/../model/m/invoke"));
        assertEquals("Cohere", name("https://api.cohere.com//v2/chat"));
        assertNull(name("https://api.cohere.com/v2/chat/../../v9/x"));
    }

    @Test
    void reportsHowItMatched() {
        MatchedPattern m = service.matchesApiPatternFromUrl("https://api.anthropic.com/v1/messages");
        assertEquals("domain", m.matchType());
        assertEquals("api.anthropic.com", m.matchValue());
        MatchedPattern byPort = service.matchesApiPatternFromUrl("http://box:11434/api/chat");
        assertEquals("path", byPort.matchType());
        assertEquals("/api/chat", byPort.matchValue());
    }

    @Test
    void loadsACustomPatternsFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p.json");
        Files.writeString(file, "{\"patterns\":[{\"name\":\"Proxy\",\"domains\":[\"llm.internal\",\"*\",\"*.*\"],"
                + "\"paths\":[\"/v1/chat\"],\"allowPathMatchAcrossDomains\":true}]}");
        PatternMatchingService custom = new PatternMatchingService(file, true);
        assertEquals(1, custom.getPatternsCount());
        assertEquals(List.of("llm.internal"), custom.getLoadedPatterns().get(0).domains());
        assertEquals("Proxy", custom.matchesApiPatternFromUrl("https://llm.internal/anything").pattern().name());
        MatchedPattern cross = custom.matchesApiPatternFromUrl("https://other.host/v1/chat/x");
        assertEquals("path", cross.matchType());
        assertNull(custom.matchesApiPatternFromUrl("https://api.openai.com/v1/embeddings"));
    }

    @Test
    void wildcardStandsForExactlyOneLabel(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p.json");
        Files.writeString(file, "{\"patterns\":[{\"name\":\"W\",\"domains\":[\"svc.**.example.com\"]}]}");
        PatternMatchingService custom = new PatternMatchingService(file, true);
        assertNotNull(custom.matchesApiPatternFromUrl("https://svc.eu.example.com/x"));
        assertNotNull(custom.matchesApiPatternFromUrl("https://a.svc.eu.example.com/x"));
        assertNull(custom.matchesApiPatternFromUrl("https://svc.eu.west.example.com/x"));
        assertNull(custom.matchesApiPatternFromUrl("https://svc..example.com/x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"patterns\":{}}",
            "{\"patterns\":[{\"name\":\"x\"}]}",
            "{\"patterns\":[{\"name\":\"x\",\"domains\":[1]}]}",
            "{\"patterns\":[{\"name\":\"x\",\"domains\":[\"a\"],\"paths\":\"/v1\"}]}",
            "{\"patterns\":[{\"name\":\"x\",\"domains\":[\"a\"],\"ports\":[\"80\"]}]}",
            "not json",
    })
    void fallsBackToTheBundledPatternsForAMalformedFile(String content, @TempDir Path dir) throws IOException {
        Path file = dir.resolve("p.json");
        Files.writeString(file, content);
        assertEquals(21, new PatternMatchingService(file, true).getPatternsCount());
    }

    @Test
    void fallsBackToTheBundledPatternsForAMissingFile(@TempDir Path dir) {
        assertEquals(21, new PatternMatchingService(dir.resolve("missing.json"), true).getPatternsCount());
    }

    @Test
    void normalizesMatchPaths() {
        assertEquals("/model/x", PatternMatchingService.normalizeMatchPath("//model/x?y=1"));
        assertEquals("/model/", PatternMatchingService.normalizeMatchPath("/x/../model/"));
        assertEquals("/a/b", PatternMatchingService.normalizeMatchPath("/a//b#f"));
    }
}
