package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.coolhandlabs.coolhand.ApiPattern;
import com.coolhandlabs.coolhand.internal.Json;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SanitizationTest {
    private final PatternMatchingService service = new PatternMatchingService(true);

    private JsonNode body(String json) {
        return service.sanitizeBody(TestSupport.json(json));
    }

    private String text(String raw) {
        return service.sanitizeBody(TextNode.valueOf(raw)).textValue();
    }

    @Nested
    class Headers {
        @Test
        void redactsDefaultCredentialHeadersAndLowercasesNames() {
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer sk-live");
            headers.put("X-API-Key", "k");
            headers.put("Cookie", "c=1");
            headers.put("Content-Type", "application/json");
            assertEquals(Map.of("authorization", "[REDACTED]", "x-api-key", "[REDACTED]", "cookie", "[REDACTED]",
                    "content-type", "application/json"), service.sanitizeHeaders(headers, null));
        }

        @Test
        void redactsCredentialLookingNamesButNotRateLimitTelemetry() {
            Map<String, Object> out = service.sanitizeHeaders(Map.of(
                    "x-auth-token", "t", "cf-access-client-secret", "s", "x-csrf-token", "c",
                    "anthropic-ratelimit-tokens-remaining", "1000", "x-request-id", "req_1"), null);
            assertEquals("[REDACTED]", out.get("x-auth-token"));
            assertEquals("[REDACTED]", out.get("cf-access-client-secret"));
            assertEquals("[REDACTED]", out.get("x-csrf-token"));
            assertEquals("1000", out.get("anthropic-ratelimit-tokens-remaining"));
            assertEquals("req_1", out.get("x-request-id"));
        }

        @Test
        void joinsRepeatedValuesAndUnwrapsSingleOnes() {
            Map<String, Object> out = service.sanitizeHeaders(Map.of("Accept", List.of("a", "b"), "X-One", List.of("1")), null);
            assertEquals("a, b", out.get("accept"));
            assertEquals("1", out.get("x-one"));
        }

        @Test
        void appliesPatternSpecificRules() {
            ApiPattern pattern = new ApiPattern(null, "P", List.of("x"), null, null, null, null,
                    Map.of("X-Custom", "[HIDDEN]", "absent", "[HIDDEN]"));
            Map<String, Object> out = service.sanitizeHeaders(Map.of("x-custom", "v", "empty", ""), pattern);
            assertEquals("[HIDDEN]", out.get("x-custom"));
            assertFalse(out.containsKey("absent"));
        }
    }

    @Nested
    class Urls {
        @Test
        void redactsCredentialQueryParameters() {
            assertEquals("https://generativelanguage.googleapis.com/v1/models/g:generateContent?key=%5BREDACTED%5D&alt=sse",
                    service.sanitizeUrl("https://generativelanguage.googleapis.com/v1/models/g:generateContent?key=AIza123&alt=sse"));
            assertEquals("https://x.example/p?X-Amz-Signature=%5BREDACTED%5D&ok=1",
                    service.sanitizeUrl("https://x.example/p?X-Amz-Signature=abc&ok=1"));
            assertEquals("https://x.example/p?api-Key=%5BREDACTED%5D", service.sanitizeUrl("https://x.example/p?api-Key=1"));
        }

        @Test
        void removesUserinfo() {
            assertEquals("https://host.example/v1", service.sanitizeUrl("https://user:pass@host.example/v1"));
        }

        @Test
        void leavesUrlsWithNothingToRedactUntouched() {
            String url = "https://API.example.com/v1/x?model=a%20b&z=1";
            assertEquals(url, service.sanitizeUrl(url));
            assertEquals("not a url", service.sanitizeUrl("not a url"));
        }
    }

    @Nested
    class JsonBodies {
        @Test
        void redactsAzureOnYourDataCredentials() {
            JsonNode out = body("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"data_sources\":[{\"type\":"
                    + "\"azure_search\",\"parameters\":{\"authentication\":{\"type\":\"api_key\",\"key\":\"live\"},"
                    + "\"connectionString\":\"AccountKey=x\",\"encoded_api_key\":\"e\"}}]}");
            JsonNode params = out.at("/data_sources/0/parameters");
            assertEquals("[REDACTED]", params.at("/authentication/key").asText());
            assertEquals("[REDACTED]", params.get("connectionString").asText());
            assertEquals("[REDACTED]", params.get("encoded_api_key").asText());
            assertEquals("hello", out.at("/messages/0/content").asText());
        }

        @Test
        void leavesMessageContentAndToolSchemasAlone() {
            String json = "{\"messages\":[{\"role\":\"user\",\"content\":\"what is my api_key?\"}],\"tools\":[{\"type\":"
                    + "\"function\",\"function\":{\"name\":\"get_key\",\"parameters\":{\"key\":{\"type\":\"string\"},"
                    + "\"password\":{\"type\":\"string\"},\"headers\":\"ok\",\"authorization\":\"ok\"}}}],\"pwd\":\"/home/me\"}";
            assertEquals(TestSupport.json(json), body(json));
        }

        @Test
        void redactsMcpAndRealtimeCredentials() {
            JsonNode out = body("{\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://mcp.example.com\",\"authorization_token\":\"t\"}],"
                    + "\"tools\":[{\"type\":\"mcp\",\"server_label\":\"d\",\"authorization\":\"Bearer a\",\"headers\":{\"X-Api-Key\":\"a\"}}],"
                    + "\"client_secret\":{\"value\":\"ek\"}}");
            assertEquals("[REDACTED]", out.at("/mcp_servers/0/authorization_token").asText());
            assertEquals("https://mcp.example.com", out.at("/mcp_servers/0/url").asText());
            assertEquals("[REDACTED]", out.at("/tools/0/authorization").asText());
            assertEquals("[REDACTED]", out.at("/tools/0/headers").asText());
            assertEquals("d", out.at("/tools/0/server_label").asText());
            assertEquals("[REDACTED]", out.get("client_secret").asText());
        }

        @Test
        void redactsGenericCredentialStringsAnywhere() {
            JsonNode out = body("{\"user\":{\"password\":\"hunter2\",\"accessToken\":\"a\",\"refresh-token\":\"r\","
                    + "\"secrets\":[\"x\"],\"API_KEY\":[1,\"k\"],\"user[password]\":\"p\",\"token\":\"logprob\"}}");
            assertEquals("[REDACTED]", out.at("/user/password").asText());
            assertEquals("[REDACTED]", out.at("/user/accessToken").asText());
            assertEquals("[REDACTED]", out.at("/user/refresh-token").asText());
            assertEquals("[REDACTED]", out.at("/user/API_KEY").asText());
            assertEquals("[REDACTED]", out.at("/user/user[password]").asText());
            assertEquals("x", out.at("/user/secrets/0").asText());
            assertEquals("logprob", out.at("/user/token").asText());
        }

        @Test
        void replacesSubtreesNestedTooDeeply() {
            String json = "{\"a\":".repeat(70) + "1" + "}".repeat(70);
            assertTrue(Json.stringify(body(json)).contains("[REDACTED: nested too deeply]"));
        }

        @Test
        void passesScalarsAndNullThrough() {
            assertNull(service.sanitizeBody(null));
            assertEquals(TestSupport.json("42"), body("42"));
            assertEquals("plain text body", text("plain text body"));
        }
    }

    @Nested
    class StringBodies {
        @Test
        void redactsNdjson() {
            String ndjson = "{\"data_sources\":[{\"parameters\":{\"key\":\"live-key\"}}]}\n{\"ok\":true}";
            String out = text(ndjson);
            assertFalse(out.contains("live-key"));
            assertEquals(2, out.split("\n").length);
        }

        @Test
        void redactsABomPrefixedDocument() {
            assertFalse(text("﻿{\"mcp_servers\":[{\"authorization_token\":\"tok\"}]}").contains("\"tok\""));
        }

        @Test
        void scrubsCredentialValuesFromATruncatedBody() {
            String out = text("{\"data_sources\":[{\"parameters\":{\"key\":\"live-key\",\"endpoint\":\"https://x\"}}],"
                    + "\"messages\":[{\"content\":\"trunc");
            assertFalse(out.contains("live-key"));
            assertTrue(out.contains("\"key\":\"[REDACTED]\""));
            assertTrue(out.contains("https://x"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "say \"hi {\"password\":\"hunter2\"}",
                "{\"a\":\"b\"}\n{\"x\":\"unterminated, {\"password\":\"hunter2\"}",
                "\"stray quote {\"api_key\": \"hunter2\", \"other\": \"ok\"} tail\"",
                "{\"api\\u005fkey\":\"hunter2\", \"x\":",
                "{\"pass\\u0077ord\":\"hunter2\", \"x\":",
                "{\"a\":\"b\",\"password\":\"hunter2-trunc",
        })
        void redactsAroundUnbalancedQuotesAndEscapes(String raw) {
            String out = text(raw);
            assertFalse(out.contains("hunter2"), out);
            assertTrue(out.contains("[REDACTED]"));
        }

        @Test
        void keepsNonCredentialValues() {
            String out = text("{\"token\":\"aaa\",\"note\":\"keep me\",\"client_secret\":\"bbb\" ,");
            assertFalse(out.contains("aaa") || out.contains("bbb"));
            assertTrue(out.contains("keep me"));
        }

        @Test
        void scansAdversarialBodiesInLinearTime() {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                text("\"" + "keykey".repeat(400_000) + " {");
                text("key".repeat(800_000) + " {");
                text("\"key ".repeat(500_000) + " {");
                assertFalse(text("[\"" + "\\\"a".repeat(120_000) + "\", {\"password\":\"hunter2\"} ").contains("hunter2"));
            });
        }

        @Test
        void redactsFormEncodedCredentials() {
            assertEquals("grant_type=refresh_token&refresh_token=[REDACTED]&client_id=abc&token=[REDACTED]"
                    + "&user%5Bpassword%5D=[REDACTED]",
                    text("grant_type=refresh_token&refresh_token=r1&client_id=abc&token=t&user%5Bpassword%5D=p"));
            assertEquals("a=1&b=2", text("a=1&b=2"));
        }
    }

    @Nested
    class Multipart {
        private String multipart(String eol, String boundary, boolean close, String[]... parts) {
            StringBuilder out = new StringBuilder();
            for (String[] p : parts) {
                out.append("--").append(boundary).append(eol).append("Content-Disposition: form-data; name=\"")
                        .append(p[0]).append('"');
                if (p.length > 2) {
                    out.append("; filename=\"").append(p[2]).append('"');
                }
                out.append(eol).append(eol).append(p[1]).append(eol);
            }
            if (close) {
                out.append("--").append(boundary).append("--").append(eol);
            }
            return out.toString();
        }

        @Test
        void redactsCredentialPartsAndKeepsTheRestVerbatim() {
            String in = multipart("\r\n", "XBOUNDARYX", true, new String[] {"model", "whisper-1"},
                    new String[] {"api_key", "sk-SECRET123"}, new String[] {"file", "audio-bytes", "a.wav"});
            String expected = multipart("\r\n", "XBOUNDARYX", true, new String[] {"model", "whisper-1"},
                    new String[] {"api_key", "[REDACTED]"}, new String[] {"file", "audio-bytes", "a.wav"});
            assertEquals(expected, text(in));
        }

        @Test
        void handlesLfLineEndings() {
            assertEquals(multipart("\n", "B", true, new String[] {"password", "[REDACTED]"}, new String[] {"note", "ok"}),
                    text(multipart("\n", "B", true, new String[] {"password", "hunter2"}, new String[] {"note", "ok"})));
        }

        @Test
        void redactsUnquotedBracketedAndBareTokenNames() {
            String in = String.join("\r\n", "--B", "Content-Disposition: form-data; name=client_secret", "", "cs-live",
                    "--B", "Content-Disposition: form-data; name=\"user[password]\"", "", "hunter2",
                    "--B", "Content-Disposition: form-data; name=\"token\"", "", "tk-live",
                    "--B", "Content-Disposition: form-data; name=\"scope\"", "", "read", "--B--", "");
            String out = text(in);
            assertFalse(out.contains("cs-live") || out.contains("hunter2") || out.contains("tk-live"), out);
            assertTrue(out.contains("read"));
            assertEquals(3, out.split("\\[REDACTED]", -1).length - 1);
        }

        @Test
        void usesThePartNameNotTheFilename() {
            String out = text(multipart("\r\n", "B", true, new String[] {"upload", "file-body", "api_key.txt"},
                    new String[] {"secret_key", "file-secret", "k.pem"}));
            assertTrue(out.contains("file-body"));
            assertFalse(out.contains("file-secret"));
            assertFalse(text("--B\r\nContent-Disposition: form-data; filename=\"x;name=foo\"; name=\"password\"\r\n\r\n"
                    + "SECRET\r\n--B--\r\n").contains("SECRET"));
        }

        @Test
        void redactsATruncatedCredentialPart() {
            String truncated = multipart("\r\n", "XBOUNDARYX", false, new String[] {"model", "m"},
                    new String[] {"api_key", "sk-SECRET123"});
            truncated = truncated.substring(0, truncated.length() - 4);
            assertFalse(text(truncated).contains("sk-SECRET"));
        }
    }
}
