package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.CollectionMethod;
import com.coolhandlabs.coolhand.CoolhandException;
import com.coolhandlabs.coolhand.CoolhandHttpException;
import com.coolhandlabs.coolhand.Pagination;
import com.coolhandlabs.coolhand.Version;
import com.coolhandlabs.coolhand.internal.EntityKeys;
import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.internal.Json;
import com.coolhandlabs.coolhand.internal.Log;
import com.coolhandlabs.coolhand.internal.UrlParts;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Request building, authentication, error shape and JSON helpers shared by every service
 * (coolhand-node's {@code BaseService}).
 *
 * <p>Two conventions, as in coolhand-node: <b>writes</b> that submit data ({@code createFeedback},
 * log submission, uploads) never throw for API or network failures; they log and return an empty
 * {@code Optional}. <b>Reads</b> and admin writes throw {@link CoolhandException} on network failure
 * or a non-JSON body and {@link CoolhandHttpException} (with {@code status()}) on a non-2xx
 * response. Invalid arguments throw {@link IllegalArgumentException} before any request is made.
 * Every method has an {@code ...Async} variant returning a {@link CompletableFuture} that completes
 * exceptionally with the same exceptions.
 */
public abstract class BaseService {
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMillis(30_000);
    static final long MAX_REQUEST_TIMEOUT_MS = 2_147_483_647L;
    static final Duration UPLOAD_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    static final String DEFAULT_BASE_URL = "https://coolhandlabs.com";

    private static volatile HttpClient sharedClient;

    protected final String apiKey;
    protected final boolean silent;
    protected final boolean debug;
    protected final boolean dryRun;
    protected final String apiEndpoint;
    protected final Duration requestTimeout;
    protected final Log log;
    private final HttpClient http;

    protected BaseService(ServiceConfig config, String endpointPath) {
        this(config, endpointPath, false);
    }

    /**
     * @param allowAnonymous let a missing or blank key through as "no key" (only for public endpoints);
     *     no {@code X-API-Key} header is sent then
     */
    protected BaseService(ServiceConfig config, String endpointPath, boolean allowAnonymous) {
        this.apiKey = normalizeApiKey(config.apiKey(), allowAnonymous);
        this.silent = config.silent();
        this.debug = config.debug();
        this.dryRun = config.dryRun();
        this.log = new Log(silent);
        Duration timeout = config.requestTimeout() == null ? DEFAULT_REQUEST_TIMEOUT : config.requestTimeout();
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() > MAX_REQUEST_TIMEOUT_MS
                || timeout.toNanos() % 1_000_000 != 0) {
            throw new IllegalArgumentException("requestTimeoutMs must be a positive integer number of milliseconds (max "
                    + MAX_REQUEST_TIMEOUT_MS + "). Got: " + timeout.toMillis());
        }
        this.requestTimeout = timeout;
        String rawBase = config.baseUrl() == null ? DEFAULT_BASE_URL : config.baseUrl();
        validateBaseUrl(rawBase);
        this.apiEndpoint = rawBase.replaceAll("/+$", "") + endpointPath;
        this.http = config.httpClient() != null ? config.httpClient() : sharedClient();
    }

    private static HttpClient sharedClient() {
        HttpClient client = sharedClient;
        if (client == null) {
            synchronized (BaseService.class) {
                client = sharedClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(30))
                            .build();
                    sharedClient = client;
                }
            }
        }
        return client;
    }

    // ------------------------------------------------------------------ validation

    private static final Pattern URL_USERINFO = Pattern.compile("^(\\s*(?:[a-z][a-z0-9+.-]*://)?)[^/\\s]*@",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_SAFE = Pattern.compile("^[\\t\\x20-\\x7e\\x80-\\xff]*$");
    private static final Pattern HTTP_WS_EDGES = Pattern.compile("^[\\t\\n\\r ]+|[\\t\\n\\r ]+$");

    /** The base URL as it may appear in a message: query/fragment dropped, userinfo masked. */
    static String redactForMessage(String raw) {
        int cut = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '?' || raw.charAt(i) == '#') {
                cut = i;
                break;
            }
        }
        String withoutQuery = cut == -1 ? raw : raw.substring(0, cut) + "…";
        return URL_USERINFO.matcher(withoutQuery).replaceFirst("$1***@");
    }

    static void validateBaseUrl(String raw) {
        String shown = redactForMessage(raw);
        UrlParts url = UrlParts.parse(raw);
        if (url == null) {
            if (raw.matches("(?i)^\\s*https?://(?:[^/?#]*@)?(?:[/?#].*)?$")) {
                throw new IllegalArgumentException("baseUrl must include a hostname. Got: \"" + shown + "\"");
            }
            throw new IllegalArgumentException("Invalid baseUrl: \"" + shown + "\" is not a valid URL");
        }
        if (url.hasCredentials()) {
            throw new IllegalArgumentException(
                    "baseUrl must not include credentials (user:password@). Use the apiKey option instead.");
        }
        if (url.query != null || url.fragment != null || raw.contains("?") || raw.contains("#")) {
            throw new IllegalArgumentException("baseUrl must not include a query string or fragment. Got: \"" + shown + "\"");
        }
        if (url.scheme.equals("https")) {
            return;
        }
        if (url.scheme.equals("http")) {
            String h = url.hostname;
            if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]")) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "baseUrl must use https:// (got: \"" + shown + "\"). For local dev, http://localhost is allowed.");
    }

    static String normalizeApiKey(String raw, boolean allowAnonymous) {
        if (allowAnonymous && (raw == null || JsText.trim(raw).isEmpty())) {
            return "";
        }
        if (raw == null || JsText.trim(raw).isEmpty()) {
            throw new IllegalArgumentException("apiKey must be a non-empty string");
        }
        String key = HTTP_WS_EDGES.matcher(raw).replaceAll("");
        if (!HEADER_SAFE.matcher(key).matches()) {
            throw new IllegalArgumentException("apiKey contains characters that are not valid in an HTTP header (e.g. a "
                    + "newline or control character). Check how the key is loaded (.env parsing, stray whitespace).");
        }
        return key;
    }

    // ------------------------------------------------------------------ masking

    /** {@code text} with every occurrence of the API key masked. */
    protected String mask(String text) {
        if (apiKey.isEmpty() || text == null) {
            return text;
        }
        return text.replace(apiKey, "[redacted]");
    }

    /** First 2000 characters of a response body for an error message, with the key masked first. */
    protected String excerpt(String text) {
        String masked = mask(text == null ? "" : text);
        return masked.length() > 2000 ? masked.substring(0, 2000) : masked;
    }

    static String formatErrorMessage(Throwable error) {
        if (error == null) {
            return "null";
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    protected String describeError(Throwable err) {
        return mask(formatErrorMessage(err));
    }

    private boolean leaksKey(Throwable err) {
        if (apiKey.isEmpty()) {
            return false;
        }
        Throwable cur = err;
        for (int depth = 0; cur != null && depth <= 5; depth++, cur = cur.getCause()) {
            if (cur.getMessage() != null && cur.getMessage().contains(apiKey)) {
                return true;
            }
        }
        return false;
    }

    private CoolhandException scrubbedError(String errorPrefix, Throwable err) {
        String message = errorPrefix + ": " + describeError(err);
        return leaksKey(err) ? new CoolhandException(message) : new CoolhandException(message, err);
    }

    // ------------------------------------------------------------------ requests

    protected HttpRequest.Builder request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", Version.userAgent());
        if (!apiKey.isEmpty()) {
            builder.header("X-API-Key", apiKey);
        }
        return builder;
    }

    /** {@code data} plus the SDK collector string for {@code method}, appended last. */
    protected static Map<String, Object> withCollector(Map<String, Object> data, CollectionMethod method) {
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("collector", CollectionMethod.collectorString(method));
        return out;
    }

    /**
     * POSTs a JSON payload, resolving to the parsed JSON on 2xx and to empty on any failure (logged,
     * never thrown); skipped (empty) in dry-run mode.
     */
    protected CompletableFuture<Optional<JsonNode>> sendRequestAsync(Object payload, String successMessage) {
        if (dryRun) {
            if (!silent) {
                log.info("🚫 DRY RUN: Skipping API call to " + apiEndpoint);
                try {
                    log.info("🚫 DRY RUN: Would send payload: " + Json.pretty(payload));
                } catch (RuntimeException e) {
                    Log.error("❌ Failed to stringify payload for dry-run log: " + describeError(e));
                }
            }
            log.info("🚫 DRY RUN: " + successMessage.replace("✅", "🚫"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            String body = Json.stringify(payload);
            if (debug && !silent) {
                log.info("[coolhand-java] DEBUG: Sending to " + apiEndpoint);
                log.info("[coolhand-java] DEBUG: Payload size: " + body.length() + " bytes");
            }
            HttpRequest request = request(apiEndpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> parseJsonResponse(response, successMessage))
                    .exceptionally(error -> {
                        Log.error("❌ Request error: " + describeError(unwrap(error)));
                        return Optional.empty();
                    });
        } catch (RuntimeException e) {
            Log.error("❌ Request error: " + describeError(e));
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private Optional<JsonNode> parseJsonResponse(HttpResponse<String> response, String successMessage) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            JsonNode parsed;
            try {
                parsed = Json.parse(response.body());
            } catch (IOException e) {
                throw new CompletionException(e);
            }
            log.info(successMessage);
            return Optional.ofNullable(parsed);
        }
        Log.error("❌ Request failed: " + status + " - " + excerpt(response.body()));
        return Optional.empty();
    }

    /** POSTs a multipart body; empty on failure (logged), skipped in dry-run mode. */
    protected CompletableFuture<Optional<JsonNode>> sendMultipartAsync(byte[] body, String boundary, String successMessage) {
        if (dryRun) {
            if (!silent) {
                log.info("🚫 DRY RUN: Skipping API call to " + apiEndpoint);
            }
            log.info("🚫 DRY RUN: " + successMessage.replace("✅", "🚫"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (debug && !silent) {
            log.info("[coolhand-java] DEBUG: Sending multipart to " + apiEndpoint);
        }
        try {
            HttpRequest request = request(apiEndpoint)
                    .timeout(UPLOAD_REQUEST_TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> parseJsonResponse(response, successMessage))
                    .exceptionally(error -> {
                        Log.error("❌ Request error: " + describeError(unwrap(error)));
                        return Optional.empty();
                    });
        } catch (RuntimeException e) {
            Log.error("❌ Request error: " + describeError(e));
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /** A response body plus its headers and status. */
    protected record RawResponse(String text, HttpHeaders headers, int status) {}

    /** A response body as bytes plus its headers. */
    protected record BytesResponse(byte[] bytes, HttpHeaders headers) {}

    /**
     * Sends {@code request} and returns the body text, failing with {@link CoolhandHttpException} on
     * a non-2xx response and {@link CoolhandException} on a network failure or redirect.
     */
    protected CompletableFuture<RawResponse> fetchWithHeaders(HttpRequest request, String errorPrefix) {
        return send(request, HttpResponse.BodyHandlers.ofByteArray(), errorPrefix).thenApply(response -> {
            String text = new String(response.body(), StandardCharsets.UTF_8);
            checkStatus(response.statusCode(), text, errorPrefix);
            return new RawResponse(text, response.headers(), response.statusCode());
        });
    }

    /** Like {@link #fetchWithHeaders}, but returns raw bytes (for file downloads). */
    protected CompletableFuture<BytesResponse> fetchBytes(HttpRequest request, String errorPrefix) {
        return send(request, HttpResponse.BodyHandlers.ofByteArray(), errorPrefix).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                checkStatus(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), errorPrefix);
            }
            return new BytesResponse(response.body(), response.headers());
        });
    }

    private void checkStatus(int status, String text, String errorPrefix) {
        if (status >= 300 && status < 400) {
            throw new CoolhandException(errorPrefix + ": redirect (" + status + ") not followed");
        }
        if (status < 200 || status >= 300) {
            throw new CoolhandHttpException(errorPrefix + " (" + status + "): " + excerpt(text), status);
        }
    }

    private <T> CompletableFuture<HttpResponse<T>> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            String errorPrefix) {
        CompletableFuture<HttpResponse<T>> future;
        try {
            future = http.sendAsync(request, handler);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(scrubbedError(errorPrefix, e));
        }
        return future.handle((response, error) -> {
            if (error != null) {
                throw scrubbedError(errorPrefix, unwrap(error));
            }
            return response;
        });
    }

    protected CompletableFuture<String> fetchOrThrow(HttpRequest request, String errorPrefix) {
        return fetchWithHeaders(request, errorPrefix).thenApply(RawResponse::text);
    }

    /** A parsed JSON body plus the response headers. */
    protected record JsonWithHeaders(JsonNode body, HttpHeaders headers, int status) {}

    /** Parses {@code text} as a JSON object or array, or throws with the noun's wording. */
    protected JsonNode parseJsonObject(String text, String noun) {
        JsonNode parsed;
        try {
            parsed = Json.parse(text);
        } catch (IOException | RuntimeException e) {
            throw new CoolhandException(noun + " response was not valid JSON: " + excerpt(text));
        }
        if (parsed == null || !parsed.isContainerNode()) {
            throw new CoolhandException(noun + " response was not a JSON object: " + excerpt(text));
        }
        return parsed;
    }

    /** GETs {@code url} and parses the JSON body, keeping the headers (e.g. for pagination). */
    protected CompletableFuture<JsonWithHeaders> getJsonWithHeaders(String url, String noun) {
        HttpRequest request;
        try {
            request = request(url).header("Accept", "application/json").GET().build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new CoolhandException(noun + " request failed: " + describeError(e)));
        }
        return fetchWithHeaders(request, noun + " request failed")
                .thenApply(raw -> new JsonWithHeaders(parseJsonObject(raw.text(), noun), raw.headers(), raw.status()));
    }

    protected CompletableFuture<JsonNode> getJson(String url, String noun) {
        return getJsonWithHeaders(url, noun).thenApply(JsonWithHeaders::body);
    }

    /** A record upserted by key, and whether the server created it (201) rather than updated it (200). */
    protected record Upserted(JsonNode record, boolean created) {}

    /**
     * {@code PUT <endpoint>/by_key/<key>}: each {@code /}-separated segment percent-encoded on its
     * own. Validates the key first (so a dry run rejects what a live run would), then resolves to
     * empty on a dry run.
     */
    protected CompletableFuture<Optional<Upserted>> putByKey(String key, Map<String, Object> body, String noun) {
        if (key == null) {
            throw new IllegalArgumentException(noun + " upsert: key must be a non-empty string");
        }
        String problem = EntityKeys.keyProblem(key);
        if (problem != null) {
            throw new IllegalArgumentException(noun + " upsert: key " + problem + " (got: \"" + key + "\")");
        }
        String[] segments = key.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        noun + " upsert: key must not contain \".\" or \"..\" path segments (got: \"" + key + "\")");
            }
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(JsText.encodeURIComponent(segment));
        }
        String url = apiEndpoint + "/by_key/" + encoded;
        if (dryRun) {
            log.info("🚫 DRY RUN: Skipping " + noun.toLowerCase(java.util.Locale.ROOT) + " upsert for key \"" + key + "\"");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        return fetchWithHeaders(request, noun + " request failed").thenApply(raw ->
                Optional.of(new Upserted(parseJsonObject(raw.text(), noun), raw.status() == 201)));
    }

    /**
     * {@code <endpoint>/<encoded id>}, rejecting ids URL resolution would retarget: blank strings and
     * the dot-segments {@code .} and {@code ..}.
     */
    protected String buildResourceUrl(String id, String errorMessage) {
        if (id == null || JsText.trim(id).isEmpty() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException(errorMessage);
        }
        return apiEndpoint + "/" + JsText.encodeURIComponent(id);
    }

    /** Serialises a {@code since}/{@code until} bound: an {@link Instant} as ISO-8601 UTC with millis. */
    protected static String toTimestampParam(Object value) {
        if (value instanceof Instant instant) {
            return JsText.isoTimestamp(instant);
        }
        return value == null ? null : value.toString();
    }

    // ------------------------------------------------------------------ pagination

    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private static long parseHeaderInt(Optional<String> value, long fallback) {
        if (value.isEmpty()) {
            return fallback;
        }
        String trimmed = JsText.trim(value.get());
        if (!DIGITS.matcher(trimmed).matches()) {
            return fallback;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Pagination from {@code X-Page}/{@code X-Per-Page}/{@code X-Total-Count}/{@code X-Total-Pages},
     * falling back to a lower-bound estimate from the page actually returned when totals are absent.
     */
    protected static Pagination paginationFromHeaders(HttpHeaders headers, int itemCount, Integer page, Integer per) {
        final int defaultPerPage = 25;
        final int maxPerPage = 100;
        int requestedPage = Math.max(1, page == null ? 1 : page);
        int truncatedPer = per == null ? 0 : per;
        int requestedPer = truncatedPer > 0 ? Math.min(truncatedPer, maxPerPage) : defaultPerPage;
        boolean hasTotalCount = headers.firstValue("x-total-count").isPresent();

        long currentPage = Math.max(1, parseHeaderInt(headers.firstValue("x-page"), requestedPage));
        long perPage = parseHeaderInt(headers.firstValue("x-per-page"), requestedPer);
        long totalCount;
        long totalPages;
        boolean hasNextPage;
        boolean hasPrevPage;
        if (hasTotalCount) {
            totalCount = parseHeaderInt(headers.firstValue("x-total-count"), itemCount);
            totalPages = parseHeaderInt(headers.firstValue("x-total-pages"),
                    perPage > 0 ? (totalCount + perPage - 1) / perPage : (totalCount > 0 ? 1 : 0));
            hasNextPage = currentPage < totalPages;
            hasPrevPage = currentPage > 1;
        } else if (itemCount > 0) {
            totalCount = perPage > 0 ? (currentPage - 1) * perPage + itemCount : itemCount;
            totalPages = perPage > 0 ? (totalCount + perPage - 1) / perPage : currentPage;
            hasNextPage = perPage > 0 && itemCount >= perPage;
            hasPrevPage = currentPage > 1;
        } else {
            totalCount = 0;
            totalPages = 0;
            hasNextPage = false;
            hasPrevPage = currentPage > 1;
        }
        return new Pagination((int) Math.min(currentPage, Integer.MAX_VALUE), (int) Math.min(perPage, Integer.MAX_VALUE),
                totalCount, totalPages, hasNextPage, hasPrevPage);
    }

    /** GETs a bare-array list endpoint and reads its pagination headers. */
    protected CompletableFuture<com.coolhandlabs.coolhand.model.Page<JsonNode>> listPage(String url, String noun,
            Integer page, Integer per) {
        return getJsonWithHeaders(url, noun).thenApply(r -> {
            java.util.List<JsonNode> items = Services.items(r.body(), noun);
            return new com.coolhandlabs.coolhand.model.Page<>(items,
                    paginationFromHeaders(r.headers(), items.size(), page, per));
        });
    }

    // ------------------------------------------------------------------ helpers

    protected void logSeparator() {
        log.info("═".repeat(60));
    }

    /** The full endpoint this service talks to. */
    public String getApiEndpoint() {
        return apiEndpoint;
    }

    static Throwable unwrap(Throwable error) {
        Throwable cur = error;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    /**
     * Waits for {@code future} and rethrows its failure as thrown (an unchecked exception or error
     * unchanged; anything else wrapped in {@link CoolhandException}).
     */
    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoolhandException("Interrupted while waiting for the Coolhand API", e);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new CoolhandException(formatErrorMessage(cause), cause);
        }
    }

    /** Query-string builder with {@code URLSearchParams.set} semantics: null skipped, last set wins. */
    protected static final class Query {
        private final Map<String, String> params = new LinkedHashMap<>();

        public Query set(String name, Object value) {
            if (value != null) {
                params.put(name, value instanceof Number n ? JsText.jsNumber(n) : String.valueOf(value));
            }
            return this;
        }

        public String appendTo(String url) {
            if (params.isEmpty()) {
                return url;
            }
            StringBuilder out = new StringBuilder(url).append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) {
                    out.append('&');
                }
                first = false;
                out.append(JsText.formEncode(e.getKey())).append('=').append(JsText.formEncode(e.getValue()));
            }
            return out.toString();
        }
    }
}
