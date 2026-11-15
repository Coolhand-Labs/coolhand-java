package com.coolhandlabs.coolhand.services;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

/** The client's realized savings, a read-only singleton keyed by the API key ({@code /api/v2/savings}). */
public final class SavingsService extends BaseService {
    public SavingsService(ServiceConfig config) {
        super(config, "/api/v2/savings");
    }

    /**
     * Fails with {@code CoolhandHttpException} on a non-2xx response; a {@code 503} means the comparison
     * could not be computed and is never zero savings.
     */
    public CompletableFuture<JsonNode> getRealizedSavingsAsync() {
        return getJson(apiEndpoint, "Savings");
    }

    public JsonNode getRealizedSavings() {
        return await(getRealizedSavingsAsync());
    }
}
