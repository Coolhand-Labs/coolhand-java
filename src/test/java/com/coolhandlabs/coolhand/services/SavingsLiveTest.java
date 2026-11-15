package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.coolhandlabs.coolhand.CoolhandHttpException;
import com.coolhandlabs.coolhand.model.SearchWorkloadsParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real Coolhand server. Skipped unless {@code COOLHAND_LIVE_BASE_URL} and
 * {@code COOLHAND_LIVE_API_KEY} (a private key) are both set.
 */
class SavingsLiveTest {
    private static String baseUrl;
    private static String apiKey;

    @BeforeAll
    static void requireLiveServer() {
        baseUrl = System.getenv("COOLHAND_LIVE_BASE_URL");
        apiKey = System.getenv("COOLHAND_LIVE_API_KEY");
        assumeTrue(baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank(),
                "COOLHAND_LIVE_BASE_URL and COOLHAND_LIVE_API_KEY not set");
    }

    private static ServiceConfig config(String key) {
        return ServiceConfig.builder().apiKey(key).baseUrl(baseUrl).build();
    }

    @Test
    void realizedSavingsHasTheDocumentedShape() {
        JsonNode savings = new SavingsService(config(apiKey)).getRealizedSavings();

        for (String field : List.of("total", "cost_at_previous_versions", "percent")) {
            assertTrue(savings.get(field).isNumber(), field + " is a number");
        }
        assertTrue(savings.get("total").doubleValue() <= savings.get("cost_at_previous_versions").doubleValue());
        assertTrue(savings.get("workloads").isArray());
        for (JsonNode row : savings.get("workloads")) {
            assertTrue(row.get("id").isTextual());
            assertTrue(row.get("name").isTextual());
            assertTrue(row.get("savings").isNumber());
            assertTrue(row.get("cost_at_previous_versions").isNumber());
            assertTrue(row.get("active_requests").isIntegralNumber());
        }
    }

    @Test
    void workloadSavingsRowsJoinToWorkloadsOnId() {
        JsonNode savings = new SavingsService(config(apiKey)).getRealizedSavings();
        List<String> ids = new WorkloadService(config(apiKey)).searchWorkloads(
                SearchWorkloadsParams.builder()
                        .includeArchived(true).includeSystem(true).per(100).build())
                .items().stream().map(w -> w.get("id").asText()).toList();

        for (JsonNode row : savings.get("workloads")) {
            assertTrue(ids.contains(row.get("id").asText()), "savings row " + row.get("id") + " matches a workload id");
        }
    }

    @Test
    void anInvalidKeyIsA401() {
        CoolhandHttpException e = assertThrows(CoolhandHttpException.class,
                () -> new SavingsService(config("ch_priv_not_a_real_key")).getRealizedSavings());
        assertEquals(401, e.status());
    }
}
