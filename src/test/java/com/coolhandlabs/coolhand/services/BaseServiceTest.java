package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.coolhandlabs.coolhand.Pagination;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BaseServiceTest {
    private static LoggingService logging(String baseUrl) {
        return new LoggingService(ServiceConfig.builder().apiKey("k").baseUrl(baseUrl).build());
    }

    private static String error(String baseUrl) {
        return assertThrows(IllegalArgumentException.class, () -> logging(baseUrl)).getMessage();
    }

    @Test
    void defaultsToTheProductionEndpoint() {
        assertEquals("https://coolhandlabs.com/api/v2/llm_request_logs",
                new LoggingService(ServiceConfig.builder().apiKey("k").build()).getApiEndpoint());
    }

    @ParameterizedTest
    @CsvSource({
            "https://feedback.example.com, https://feedback.example.com/api/v2/llm_request_logs",
            "https://feedback.example.com///, https://feedback.example.com/api/v2/llm_request_logs",
            "https://example.com/prefix, https://example.com/prefix/api/v2/llm_request_logs",
            "http://localhost:3000, http://localhost:3000/api/v2/llm_request_logs",
            "http://127.0.0.1:3000, http://127.0.0.1:3000/api/v2/llm_request_logs",
            "http://[::1]:3000, http://[::1]:3000/api/v2/llm_request_logs",
    })
    void acceptsValidBaseUrls(String baseUrl, String endpoint) {
        assertEquals(endpoint, logging(baseUrl).getApiEndpoint());
    }

    @Test
    void rejectsInvalidBaseUrlsWithoutEchoingSecrets() {
        assertEquals("baseUrl must use https:// (got: \"http://example.com\"). For local dev, http://localhost is allowed.",
                error("http://example.com"));
        assertEquals("baseUrl must not include credentials (user:password@). Use the apiKey option instead.",
                error("https://user:secret@example.com"));
        assertEquals("baseUrl must not include a query string or fragment. Got: \"https://example.com/…\"",
                error("https://example.com/?apikey=secret"));
        assertEquals("Invalid baseUrl: \"not a url\" is not a valid URL", error("not a url"));
        assertEquals("baseUrl must use https:// (got: \"ftp://example.com\"). For local dev, http://localhost is allowed.",
                error("ftp://example.com"));
    }

    @Test
    void validatesTheApiKey() {
        assertEquals("apiKey must be a non-empty string", assertThrows(IllegalArgumentException.class,
                () -> new LoggingService(ServiceConfig.builder().apiKey("  ").build())).getMessage());
        String bad = assertThrows(IllegalArgumentException.class,
                () -> new LoggingService(ServiceConfig.builder().apiKey("sk-1\nsk-2").build())).getMessage();
        assertEquals(false, bad.contains("sk-1"));
        assertEquals("abc", BaseService.normalizeApiKey(" \tabc\r\n", false));
        assertEquals("", BaseService.normalizeApiKey(null, true));
    }

    @Test
    void validatesTheTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoggingService(ServiceConfig.builder().apiKey("k").requestTimeout(Duration.ZERO).build()));
        assertThrows(IllegalArgumentException.class,
                () -> new LoggingService(ServiceConfig.builder().apiKey("k").requestTimeout(Duration.ofNanos(1500)).build()));
        new LoggingService(ServiceConfig.builder().apiKey("k").requestTimeout(Duration.ofMillis(1)).build());
    }

    private static HttpHeaders headers(Map<String, String> map) {
        java.util.Map<String, List<String>> m = new java.util.HashMap<>();
        map.forEach((k, v) -> m.put(k, List.of(v)));
        return HttpHeaders.of(m, (a, b) -> true);
    }

    @Test
    void paginationReadsHeadersWhenPresent() {
        Pagination p = BaseService.paginationFromHeaders(headers(Map.of("x-page", "2", "x-per-page", "10",
                "x-total-count", "35", "x-total-pages", "4")), 10, 2, 10);
        assertEquals(new Pagination(2, 10, 35, 4, true, true), p);
    }

    @Test
    void paginationDerivesMissingTotalPagesFromTheCount() {
        assertEquals(new Pagination(1, 10, 35, 4, true, false),
                BaseService.paginationFromHeaders(headers(Map.of("x-per-page", "10", "x-total-count", "35")), 10, null, null));
    }

    @Test
    void paginationEstimatesALowerBoundWithoutTotals() {
        assertEquals(new Pagination(3, 25, 75, 3, true, true),
                BaseService.paginationFromHeaders(headers(Map.of()), 25, 3, null));
        assertEquals(new Pagination(1, 25, 0, 0, false, false),
                BaseService.paginationFromHeaders(headers(Map.of()), 0, null, null));
        assertEquals(new Pagination(1000000, 100, 0, 0, false, true),
                BaseService.paginationFromHeaders(headers(Map.of()), 0, 1000000, 500));
    }

    @Test
    void paginationTreatsMalformedHeadersAsAbsent() {
        assertEquals(new Pagination(2, 25, 3, 1, false, true),
                BaseService.paginationFromHeaders(headers(Map.of("x-page", "abc", "x-total-count", "3",
                        "x-per-page", "", "x-total-pages", "1.5")), 3, 2, null));
    }

    @Test
    void redactsBaseUrlsForMessages() {
        assertEquals("https://***@example.com/x", BaseService.redactForMessage("https://user:pw@example.com/x"));
        assertEquals("https://example.com/…", BaseService.redactForMessage("https://example.com/?key=1"));
    }
}
