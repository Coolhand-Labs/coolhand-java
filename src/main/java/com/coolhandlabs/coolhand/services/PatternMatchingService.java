package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.ApiPattern;
import com.coolhandlabs.coolhand.MatchedPattern;
import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.internal.Json;
import com.coolhandlabs.coolhand.internal.Log;
import com.coolhandlabs.coolhand.internal.UrlFilters;
import com.coolhandlabs.coolhand.internal.UrlParts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Recognises LLM API requests and redacts credentials from what gets logged: a port of
 * coolhand-node's {@code PatternMatchingService}. Patterns come from the bundled
 * {@code api-patterns.json} (identical to coolhand-node's) or a custom {@code patternsFile}.
 *
 * <p>Redaction covers request/response headers (credential names and pattern-specific headers),
 * URL query parameters and userinfo, and bodies: JSON (credential keys, Azure "On Your Data"
 * {@code data_sources}, MCP server credentials), NDJSON, {@code application/x-www-form-urlencoded},
 * {@code multipart/form-data} and unparseable or truncated JSON-like text.
 */
public final class PatternMatchingService {
    private static final String BUNDLED_PATTERNS = "/com/coolhandlabs/coolhand/api-patterns.json";

    private static final List<String> DEFAULT_REDACTED_HEADERS = List.of(
            "authorization", "api-key", "x-api-key", "cookie", "set-cookie", "proxy-authorization",
            "openai-api-key", "x-goog-api-key", "cf-aig-authorization", "x-amz-security-token",
            "ocp-apim-subscription-key", "subscription-key", "xi-api-key");

    private static final Pattern CREDENTIAL_HEADER_PATTERN =
            Pattern.compile("auth|key|token|secret|cookie|passw|credential|bearer|jwt|signature");
    private static final Pattern CREDENTIAL_HEADER_EXEMPT_PATTERN = Pattern.compile("rate-?limit");
    private static final Pattern WILDCARD_ONLY_DOMAIN = Pattern.compile("^[*.\\s]*$");

    private static final Set<String> SENSITIVE_QUERY_PARAMS = normalizedSet(
            "key", "api_key", "apikey", "token", "access_token", "secret", "password", "signature", "sig",
            "x-goog-api-key", "x-amz-signature", "x-amz-credential", "x-amz-security-token", "subscription-key",
            "ocp-apim-subscription-key", "x-api-key", "client_secret", "refresh_token", "id_token", "authorization",
            "api_token", "auth_token", "bearer_token", "secret_key", "private_key", "access_key", "auth", "bearer",
            "pwd", "x-goog-signature", "x-goog-credential", "session_token", "oauth_token", "passwd",
            "secret_access_key");

    private static final List<String> CREDENTIAL_KEY_FRAGMENTS =
            List.of("key", "secret", "password", "passwd", "pwd", "token", "credential", "connectionstring");
    private static final Set<String> ALWAYS_REDACTED_BODY_KEYS = Set.of("authorizationtoken", "clientsecret");
    private static final Set<String> ALWAYS_REDACTED_BODY_STRING_KEYS = Set.of(
            "accesstoken", "refreshtoken", "idtoken", "apikey", "xapikey", "password", "secret", "secretkey",
            "privatekey", "apitoken", "authtoken", "bearertoken", "accesskey", "sessiontoken", "passwd",
            "secretaccesskey", "oauthtoken", "newpassword", "oldpassword", "currentpassword", "confirmpassword",
            "passwordconfirmation", "openaiapikey", "anthropicapikey", "awssessiontoken", "awssecretaccesskey",
            "googleapikey", "anthropicauthtoken", "githubtoken", "apisecret");
    private static final Set<String> FORM_ONLY_CREDENTIAL_KEYS = Set.of("token", "assertion", "clientassertion");
    private static final Set<String> MCP_CREDENTIAL_KEYS = Set.of("authorization", "headers");
    private static final int MAX_BODY_DEPTH = 64;
    private static final int MAX_KEY_AFFIX = 200;

    private static final List<Pattern> MULTIPART_PARAM_PATTERNS = List.of(
            Pattern.compile(";\\s*([\\w*-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^;\\s]*))"),
            Pattern.compile(";\\s*([\\w*-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))"));
    private static final Pattern MULTIPART_DELIMITER = Pattern.compile(
            "(?:^\\s*|\\n)(--[^\\r\\n]{1,200})\\r?\\n(?:[^\\r\\n]*\\r?\\n){0,4}?content-disposition:\\s*form-data",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIAL_WORD_PATTERN = Pattern.compile(String.join("|",
            escapeAwareWord("key"), escapeAwareWord("secret"), escapeAwareWord("password"),
            escapeAwareWord("token"), escapeAwareWord("authorization"),
            escapeAwareWord("connection") + "(?:_|\\\\u005f)?" + escapeAwareWord("string")),
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_TAIL = Pattern.compile("\\s*:\\s*\"");

    private final boolean silent;
    private final Log log;
    private final List<ApiPattern> apiPatterns;
    private final Map<String, Pattern> wildcardDomainCache = new ConcurrentHashMap<>();

    /** Uses the bundled patterns. */
    public PatternMatchingService(boolean silent) {
        this(null, silent);
    }

    /**
     * Loads {@code customPatternsFile} when given, falling back to the bundled patterns (with a log
     * message) when it is missing, unreadable or not shaped {@code { patterns: [{ domains: [...] }] }}.
     */
    public PatternMatchingService(Path customPatternsFile, boolean silent) {
        this.silent = silent;
        this.log = new Log(silent);
        List<ApiPattern> loaded = null;
        if (customPatternsFile != null) {
            loaded = loadCustomPatterns(customPatternsFile);
        }
        this.apiPatterns = loaded != null ? loaded : loadDefaultPatterns();
    }

    // ------------------------------------------------------------------ loading

    private List<ApiPattern> loadDefaultPatterns() {
        List<ApiPattern> patterns = bundledPatterns();
        log.info("📋 Loaded " + patterns.size() + " default API patterns");
        return patterns;
    }

    /** The bundled {@code api-patterns.json}. */
    public static List<ApiPattern> bundledPatterns() {
        try (InputStream in = PatternMatchingService.class.getResourceAsStream(BUNDLED_PATTERNS)) {
            if (in == null) {
                throw new IllegalStateException("api-patterns.json missing from the coolhand-java jar");
            }
            JsonNode root = Json.MAPPER.readTree(in);
            return List.copyOf(Json.MAPPER.convertValue(root.get("patterns"), new TypeReference<List<ApiPattern>>() {}));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the bundled api-patterns.json", e);
        }
    }

    private List<ApiPattern> loadCustomPatterns(Path file) {
        Path resolved = file.toAbsolutePath();
        try {
            if (!Files.exists(resolved)) {
                log.warnUnlessSilent("⚠️  API patterns file not found: " + resolved + ". Falling back to default patterns.");
                return null;
            }
            JsonNode data = Json.parse(Files.readString(resolved, StandardCharsets.UTF_8));
            List<ApiPattern> patterns = validatePatternsShape(data, resolved.toString());
            log.info("📋 Loaded " + patterns.size() + " API patterns from custom patterns file");
            return patterns;
        } catch (IOException | RuntimeException e) {
            log.warnUnlessSilent("❌ Error loading API patterns: " + e.getMessage());
            return null;
        }
    }

    private static boolean isStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private List<ApiPattern> validatePatternsShape(JsonNode data, String sourceFile) {
        JsonNode patterns = data == null ? null : data.get("patterns");
        boolean valid = patterns != null && patterns.isArray();
        if (valid) {
            for (JsonNode p : patterns) {
                JsonNode paths = p.get("paths");
                JsonNode ports = p.get("ports");
                boolean portsOk = ports == null || ports.isNull()
                        || (ports.isArray() && allIntegers(ports));
                if (!p.isObject() || !isStringArray(p.get("domains"))
                        || !(paths == null || paths.isNull() || isStringArray(paths)) || !portsOk) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw new IllegalArgumentException("Coolhand: patterns file \"" + sourceFile + "\" is not shaped correctly "
                    + "(expected { patterns: [{ domains: string[], paths?: string[], ports?: number[], ... }] })");
        }
        List<ApiPattern> parsed = Json.MAPPER.convertValue(patterns, new TypeReference<List<ApiPattern>>() {});
        List<ApiPattern> out = new ArrayList<>(parsed.size());
        for (ApiPattern pattern : parsed) {
            List<String> domains = new ArrayList<>();
            for (String domain : pattern.domains()) {
                if (WILDCARD_ONLY_DOMAIN.matcher(domain).matches()) {
                    log.warnUnlessSilent("⚠️  Coolhand: ignoring domain \"" + domain + "\" in \"" + pattern.name() + "\" ("
                            + sourceFile + ") — it would match every host");
                } else {
                    domains.add(domain);
                }
            }
            out.add(domains.size() == pattern.domains().size() ? pattern
                    : new ApiPattern(pattern.id(), pattern.name(), domains, pattern.paths(),
                            pattern.allowPathMatchAcrossDomains(), pattern.requiresPathMatch(), pattern.ports(),
                            pattern.headers()));
        }
        return List.copyOf(out);
    }

    private static boolean allIntegers(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isIntegralNumber() && !(item.isNumber() && item.asDouble() == Math.rint(item.asDouble()))) {
                return false;
            }
        }
        return true;
    }

    /** The patterns in use. */
    public List<ApiPattern> getLoadedPatterns() {
        return apiPatterns;
    }

    public int getPatternsCount() {
        return apiPatterns.size();
    }

    // ------------------------------------------------------------------ matching

    // Canonical path for paths/requiresPathMatch matching: query dropped, dot-segments resolved,
    // duplicate slashes collapsed.
    static String normalizeMatchPath(String path) {
        String raw = path.split("[?#]", 2)[0];
        String resolved = raw.replaceFirst("^/+", "");
        return UrlParts.removeDotSegments("/" + resolved).replaceAll("/{2,}", "/");
    }

    private boolean hostnameMatchesDomain(String hostname, String domain) {
        if (domain.contains("*")) {
            return !WILDCARD_ONLY_DOMAIN.matcher(domain).matches() && wildcardDomainRegex(domain).matcher(hostname).find();
        }
        String lowerDomain = domain.toLowerCase(Locale.ROOT);
        return hostname.equals(lowerDomain) || hostname.endsWith("." + lowerDomain);
    }

    // A '*' stands for exactly one DNS label; like plain domains, it also matches subdomains in front.
    private Pattern wildcardDomainRegex(String domain) {
        return wildcardDomainCache.computeIfAbsent(domain, d -> {
            String collapsed = d.replaceAll("\\*+", "*");
            String body = Arrays.stream(collapsed.split("\\*", -1))
                    .map(Pattern::quote)
                    .collect(Collectors.joining("[a-z0-9-]+"));
            return Pattern.compile("(^|\\.)" + body + "$", Pattern.CASE_INSENSITIVE);
        });
    }

    private static boolean pathnameMatchesEntry(String pathname, String entry) {
        if (!pathname.startsWith(entry)) {
            return false;
        }
        return entry.endsWith("/") || pathname.length() == entry.length() || pathname.charAt(entry.length()) == '/';
    }

    private MatchedPattern findDomainMatch(String hostname, Integer port, String path) {
        String pathname = null;
        String lowerHostname = UrlFilters.normalizeHostname(hostname);
        for (ApiPattern pattern : apiPatterns) {
            String matchedDomain = null;
            for (String domain : pattern.domains()) {
                if (hostnameMatchesDomain(lowerHostname, domain)) {
                    matchedDomain = domain;
                    break;
                }
            }
            boolean portMatches = Boolean.TRUE.equals(pattern.requiresPathMatch()) && port != null && pattern.ports() != null
                    && pattern.ports().contains(port);
            if (matchedDomain == null && !portMatches) {
                continue;
            }
            if (!Boolean.TRUE.equals(pattern.requiresPathMatch())) {
                if (matchedDomain == null) {
                    continue;
                }
                return new MatchedPattern(pattern, "domain", matchedDomain);
            }
            if (path == null) {
                continue;
            }
            if (pathname == null) {
                pathname = normalizeMatchPath(path);
            }
            String matchedPath = null;
            if (pattern.paths() != null) {
                for (String entry : pattern.paths()) {
                    if (pathnameMatchesEntry(pathname, entry)) {
                        matchedPath = entry;
                        break;
                    }
                }
            }
            if (matchedPath == null) {
                continue;
            }
            return matchedDomain != null
                    ? new MatchedPattern(pattern, "domain", matchedDomain)
                    : new MatchedPattern(pattern, "path", matchedPath);
        }
        return null;
    }

    /**
     * The provider pattern {@code url} belongs to, or {@code null} when it is not a recognised LLM
     * endpoint (including when it does not parse). Never throws.
     */
    public MatchedPattern matchesApiPatternFromUrl(String url) {
        try {
            UrlParts parsed = UrlParts.parse(url);
            if (parsed == null) {
                return null;
            }
            MatchedPattern domainMatch = findDomainMatch(parsed.hostname, parsed.port, parsed.path);
            if (domainMatch != null) {
                return domainMatch;
            }
            for (ApiPattern pattern : apiPatterns) {
                if (pattern.paths() != null && Boolean.TRUE.equals(pattern.allowPathMatchAcrossDomains())) {
                    String normalized = normalizeMatchPath(parsed.path);
                    for (String pathPattern : pattern.paths()) {
                        if (normalized.contains(pathPattern)) {
                            return new MatchedPattern(pattern, "path", pathPattern);
                        }
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warnUnlessSilent("⚠️  Coolhand: pattern matching failed, request will not be monitored: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ headers

    private static boolean isCredentialHeaderName(String lowerName) {
        return CREDENTIAL_HEADER_PATTERN.matcher(lowerName).find()
                && !CREDENTIAL_HEADER_EXEMPT_PATTERN.matcher(lowerName).find();
    }

    /**
     * Headers as they are logged: names lowercased, repeated values joined with {@code ", "},
     * credential headers replaced with {@code [REDACTED]}, then the pattern's own header rules applied.
     *
     * @param headers name to value, where a value may be a {@code String} or a collection of them
     * @param pattern the matched provider pattern, or {@code null}
     */
    public Map<String, Object> sanitizeHeaders(Map<String, ?> headers, ApiPattern pattern) {
        Map<String, List<Object>> collected = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, ?> e : headers.entrySet()) {
                if (e.getKey() == null) {
                    continue; // e.g. the status line HttpURLConnection reports under a null name
                }
                String name = e.getKey().toLowerCase(Locale.ROOT);
                List<Object> values = collected.computeIfAbsent(name, k -> new ArrayList<>());
                Object value = e.getValue();
                if (value instanceof Collection<?> many) {
                    values.addAll(many);
                } else {
                    values.add(value);
                }
            }
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> e : collected.entrySet()) {
            List<Object> values = e.getValue();
            sanitized.put(e.getKey(), values.size() == 1 ? values.get(0)
                    : values.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        for (String name : DEFAULT_REDACTED_HEADERS) {
            if (sanitized.get(name) != null) {
                sanitized.put(name, "[REDACTED]");
            }
        }
        for (Map.Entry<String, Object> e : sanitized.entrySet()) {
            if (e.getValue() != null && isCredentialHeaderName(e.getKey())) {
                e.setValue("[REDACTED]");
            }
        }
        if (pattern != null && pattern.headers() != null) {
            for (Map.Entry<String, String> rule : pattern.headers().entrySet()) {
                String lowerKey = rule.getKey().toLowerCase(Locale.ROOT);
                Object current = sanitized.get(lowerKey);
                if (current != null && !"".equals(current)) {
                    sanitized.put(lowerKey, rule.getValue());
                }
            }
        }
        return sanitized;
    }

    // ------------------------------------------------------------------ URLs

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static Set<String> normalizedSet(String... names) {
        return Arrays.stream(names).map(PatternMatchingService::normalizeKey).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@code url} with credential query parameters replaced by {@code [REDACTED]} and any
     * {@code user:pass@} removed; returned unchanged when there is nothing to redact.
     */
    public String sanitizeUrl(String url) {
        try {
            UrlParts parts = UrlParts.parse(url);
            if (parts == null) {
                return url;
            }
            boolean redacted = parts.hasCredentials();
            String newQuery = null;
            if (parts.query != null && !parts.query.isEmpty()) {
                List<String> rebuilt = new ArrayList<>();
                boolean paramRedacted = false;
                for (String pair : parts.query.split("&", -1)) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    String name = JsText.decodeFormLenient(eq < 0 ? pair : pair.substring(0, eq));
                    String value = eq < 0 ? "" : JsText.decodeFormLenient(pair.substring(eq + 1));
                    if (SENSITIVE_QUERY_PARAMS.contains(normalizeKey(name))) {
                        value = "[REDACTED]";
                        paramRedacted = true;
                    }
                    rebuilt.add(JsText.formEncode(name) + "=" + JsText.formEncode(value));
                }
                if (paramRedacted) {
                    newQuery = String.join("&", rebuilt);
                    redacted = true;
                }
            }
            return redacted ? parts.href(true, newQuery) : url;
        } catch (RuntimeException e) {
            return url;
        }
    }

    // ------------------------------------------------------------------ bodies

    private static boolean isStringOrStringArray(JsonNode val, int depth) {
        if (val.isTextual()) {
            return true;
        }
        if (!val.isArray() || depth >= MAX_BODY_DEPTH) {
            return false;
        }
        for (JsonNode item : val) {
            if (isStringOrStringArray(item, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern BRACKETS = Pattern.compile("[\\[\\]]+");

    private static boolean matchesCredentialName(String name, Set<String> names, String normalizedName) {
        if (names.contains(normalizedName)) {
            return true;
        }
        if (!name.contains("[")) {
            return false;
        }
        for (String segment : BRACKETS.split(name)) {
            String trimmed = JsText.trim(segment);
            if (!trimmed.isEmpty() && names.contains(normalizeKey(trimmed))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCredentialStringKey(String name, String normalizedName) {
        return matchesCredentialName(name, ALWAYS_REDACTED_BODY_STRING_KEYS, normalizedName);
    }

    private static boolean isFormCredentialKey(String name) {
        String normalizedName = normalizeKey(name);
        return isCredentialStringKey(name, normalizedName)
                || matchesCredentialName(name, ALWAYS_REDACTED_BODY_KEYS, normalizedName)
                || matchesCredentialName(name, FORM_ONLY_CREDENTIAL_KEYS, normalizedName);
    }

    private static final Pattern FORM_JSON_START = Pattern.compile("^[{\\[\"]");
    private static final Pattern PCT_5B = Pattern.compile("%5B", Pattern.CASE_INSENSITIVE);
    private static final Pattern PCT_5D = Pattern.compile("%5D", Pattern.CASE_INSENSITIVE);

    static String redactFormEncodedFields(String rawText) {
        String text = JsText.trim(rawText);
        if (!text.contains("=") || FORM_JSON_START.matcher(text).find()) {
            return rawText;
        }
        boolean changed = false;
        String[] pairs = text.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq == -1) {
                continue;
            }
            String name = pair.substring(0, eq).replace('+', ' ');
            try {
                name = JsText.decode(name, false);
            } catch (IllegalArgumentException e) {
                name = PCT_5D.matcher(PCT_5B.matcher(name).replaceAll("[")).replaceAll("]");
            }
            if (!isFormCredentialKey(JsText.trim(name))) {
                continue;
            }
            changed = true;
            pairs[i] = pair.substring(0, eq + 1) + "[REDACTED]";
        }
        return changed ? String.join("&", pairs) : rawText;
    }

    private static final Pattern PART_SEPARATOR = Pattern.compile("\\r?\\n\\r?\\n");
    private static final Pattern TRAILING_NEWLINE = Pattern.compile("\\r?\\n\\z");
    private static final Pattern LEADING_WS = Pattern.compile("^\\s*");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("^'(.*)'$", Pattern.DOTALL);
    private static final Pattern BACKSLASH_ESCAPE = Pattern.compile("\\\\(.)", Pattern.DOTALL);
    private static final Pattern RFC5987_PREFIX = Pattern.compile("^[^']*'[^']*'");

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && JsText.isJsWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Multipart redaction; {@code null} when {@code text} is not multipart. */
    static String redactMultipartFields(String text) {
        Matcher delimiterMatch = MULTIPART_DELIMITER.matcher(text);
        if (!delimiterMatch.find()) {
            return null;
        }
        String delimiter = trimEnd(delimiterMatch.group(1));
        Matcher lead = LEADING_WS.matcher(text);
        String leading = lead.find() ? lead.group() : "";
        Pattern delimiterPattern = Pattern.compile(
                "(?<=^|\\n)" + Pattern.quote(delimiter) + "(?=(?:--)?[ \\t]*(?:\\r?\\n|\\z))");
        boolean changed = false;
        String[] pieces = delimiterPattern.split(text.substring(leading.length()), -1);
        for (int index = 0; index < pieces.length; index++) {
            String piece = pieces[index];
            if (index == 0 || piece.startsWith("--")) {
                continue;
            }
            Matcher separator = PART_SEPARATOR.matcher(piece);
            if (!separator.find()) {
                continue;
            }
            String headers = piece.substring(0, separator.start());
            boolean isCredentialPart = false;
            for (int pass = 0; pass < MULTIPART_PARAM_PATTERNS.size() && !isCredentialPart; pass++) {
                Matcher param = MULTIPART_PARAM_PATTERNS.get(pass).matcher(headers);
                while (param.find()) {
                    String key = param.group(1).toLowerCase(Locale.ROOT);
                    if (!key.equals("name") && !key.equals("name*")) {
                        continue;
                    }
                    String name = param.group(2) != null ? param.group(2)
                            : SINGLE_QUOTED.matcher(param.group(3)).replaceAll("$1");
                    if (pass == 0) {
                        name = BACKSLASH_ESCAPE.matcher(name).replaceAll("$1");
                    }
                    if (key.equals("name*")) {
                        name = RFC5987_PREFIX.matcher(name).replaceFirst("");
                    }
                    try {
                        name = JsText.decode(name, false);
                    } catch (IllegalArgumentException ignored) {
                        // keep the raw name
                    }
                    if (isFormCredentialKey(JsText.trim(name))) {
                        isCredentialPart = true;
                        break;
                    }
                }
            }
            int valueStart = separator.end();
            String value = piece.substring(valueStart);
            Matcher tn = TRAILING_NEWLINE.matcher(value);
            String trailingNewline = tn.find() ? tn.group() : "";
            if (isCredentialPart) {
                changed = true;
                pieces[index] = piece.substring(0, valueStart) + "[REDACTED]" + trailingNewline;
                continue;
            }
            String plainValue = value.substring(0, value.length() - trailingNewline.length());
            String scrubbed = redactFormEncodedFields(plainValue);
            if (!scrubbed.equals(plainValue)) {
                changed = true;
                pieces[index] = piece.substring(0, valueStart) + scrubbed + trailingNewline;
            }
        }
        return changed ? leading + String.join(delimiter, pieces) : text;
    }

    private static String escapeAwareWord(String word) {
        StringBuilder out = new StringBuilder();
        for (char ch : word.toCharArray()) {
            out.append("(?:").append(ch)
                    .append("|\\\\u").append(String.format("%04x", (int) Character.toLowerCase(ch)))
                    .append("|\\\\u").append(String.format("%04x", (int) Character.toUpperCase(ch)))
                    .append(')');
        }
        return out.toString();
    }

    /**
     * Replaces the string value of every {@code "<key containing a credential word>": "..."} in an
     * unparseable (malformed or truncated) body, including an unterminated value.
     */
    static String redactCredentialStrings(String text) {
        Matcher words = CREDENTIAL_WORD_PATTERN.matcher(text);
        Matcher keyTail = KEY_TAIL.matcher(text);
        int prevQuote = -1;
        int nextQuote = text.indexOf('"');
        StringBuilder out = new StringBuilder();
        int last = 0;
        int searchFrom = 0;
        while (searchFrom <= text.length() && words.find(searchFrom)) {
            int index = words.start();
            int wordEnd = words.end();
            searchFrom = wordEnd;
            while (nextQuote != -1 && nextQuote < index) {
                prevQuote = nextQuote;
                nextQuote = text.indexOf('"', nextQuote + 1);
            }
            if (prevQuote == -1 || nextQuote == -1) {
                continue;
            }
            if (index - prevQuote - 1 > MAX_KEY_AFFIX || nextQuote - wordEnd > MAX_KEY_AFFIX) {
                continue;
            }
            keyTail.region(nextQuote + 1, text.length());
            if (!keyTail.lookingAt()) {
                continue;
            }
            int valueStart = keyTail.end();
            int end = valueStart;
            while (end < text.length() && text.charAt(end) != '"') {
                end += text.charAt(end) == '\\' ? 2 : 1;
            }
            end = Math.min(end, text.length());
            out.append(text, last, valueStart).append("[REDACTED]");
            last = end;
            searchFrom = end;
        }
        return out.append(text.substring(last)).toString();
    }

    private JsonNode redactBodyCredentials(JsonNode value, boolean insideDataSources, int depth) {
        if (depth > MAX_BODY_DEPTH) {
            return TextNode.valueOf("[REDACTED: nested too deeply]");
        }
        if (value.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode(value.size());
            for (JsonNode item : value) {
                out.add(redactBodyCredentials(item, insideDataSources, depth + 1));
            }
            return out;
        }
        if (value.isObject()) {
            boolean isMcp = "mcp".equals(value.path("type").isTextual() ? value.get("type").textValue() : null)
                    || value.has("server_url") || value.has("serverUrl") || value.has("server_label")
                    || value.has("serverLabel");
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                String key = field.getKey();
                JsonNode val = field.getValue();
                String normalizedKey = normalizeKey(key);
                boolean nowInside = insideDataSources || normalizedKey.equals("datasources");
                boolean redact = (nowInside && CREDENTIAL_KEY_FRAGMENTS.stream().anyMatch(normalizedKey::contains))
                        || matchesCredentialName(key, ALWAYS_REDACTED_BODY_KEYS, normalizedKey)
                        || (isCredentialStringKey(key, normalizedKey) && isStringOrStringArray(val, 0))
                        || (isMcp && MCP_CREDENTIAL_KEYS.contains(normalizedKey));
                out.set(key, redact ? TextNode.valueOf("[REDACTED]") : redactBodyCredentials(val, nowInside, depth + 1));
            }
            return out;
        }
        return value;
    }

    private String sanitizeStringBody(String text) {
        String stripped = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        try {
            return Json.stringify(redactBodyCredentials(Json.parse(stripped), false, 0));
        } catch (IOException | RuntimeException ignored) {
            // not a single JSON document
        }
        String[] lines = stripped.split("\n", -1);
        if (lines.length > 1) {
            try {
                List<String> out = new ArrayList<>(lines.length);
                for (String line : lines) {
                    out.add(JsText.trim(line).isEmpty() ? line
                            : Json.stringify(redactBodyCredentials(Json.parse(line), false, 0)));
                }
                return String.join("\n", out);
            } catch (IOException | RuntimeException ignored) {
                // not NDJSON either
            }
        }
        String multipart = redactMultipartFields(stripped);
        if (multipart != null) {
            return redactCredentialStrings(multipart);
        }
        return redactCredentialStrings(redactFormEncodedFields(text));
    }

    /**
     * A parsed body with credentials redacted. Text bodies are treated as JSON, NDJSON, multipart,
     * form-encoded or, failing all of those, scrubbed by key name. Fails closed: returns {@code null}
     * if redaction itself fails.
     */
    public JsonNode sanitizeBody(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return body;
        }
        try {
            if (body.isTextual()) {
                return body.textValue().isEmpty() ? body : TextNode.valueOf(sanitizeStringBody(body.textValue()));
            }
            if (!body.isContainerNode()) {
                return body;
            }
            return redactBodyCredentials(body, false, 0);
        } catch (RuntimeException | StackOverflowError e) {
            log.warnUnlessSilent("⚠️  Coolhand: body sanitization failed, request body will not be captured: " + e.getMessage());
            return null;
        }
    }

    boolean silent() {
        return silent;
    }
}
