package com.coolhandlabs.coolhand;

import com.coolhandlabs.coolhand.interception.RequestMonitor;
import com.coolhandlabs.coolhand.internal.DeclaredContexts;
import com.coolhandlabs.coolhand.internal.Log;
import com.coolhandlabs.coolhand.internal.UrlFilters;
import com.coolhandlabs.coolhand.model.ClientFile;
import com.coolhandlabs.coolhand.model.ClientFileUpload;
import com.coolhandlabs.coolhand.model.BulkLinkFeedbackResult;
import com.coolhandlabs.coolhand.model.DownloadedClientFile;
import com.coolhandlabs.coolhand.model.GetLogContentOptions;
import com.coolhandlabs.coolhand.model.ListClientFilesParams;
import com.coolhandlabs.coolhand.model.ListReferencedFileSessionsParams;
import com.coolhandlabs.coolhand.model.ListStepTemplatesParams;
import com.coolhandlabs.coolhand.model.MetricsWindow;
import com.coolhandlabs.coolhand.model.OptimizationFeedbackLink;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchFeedbackParams;
import com.coolhandlabs.coolhand.model.SearchHelpArticlesParams;
import com.coolhandlabs.coolhand.model.SearchLogsParams;
import com.coolhandlabs.coolhand.model.SearchReferencedFilesParams;
import com.coolhandlabs.coolhand.model.SearchTemplatesParams;
import com.coolhandlabs.coolhand.model.SearchWorkloadsParams;
import com.coolhandlabs.coolhand.model.UpsertResult;
import com.coolhandlabs.coolhand.model.UpsertTemplateParams;
import com.coolhandlabs.coolhand.model.UpsertWorkloadParams;
import com.coolhandlabs.coolhand.services.BaseService;
import com.coolhandlabs.coolhand.services.ClientFileService;
import com.coolhandlabs.coolhand.services.FeedbackService;
import com.coolhandlabs.coolhand.services.HelpCenterDocsService;
import com.coolhandlabs.coolhand.services.LlmReferenceService;
import com.coolhandlabs.coolhand.services.LoggingService;
import com.coolhandlabs.coolhand.services.OptimizationFeedbackLinkService;
import com.coolhandlabs.coolhand.services.PatternMatchingService;
import com.coolhandlabs.coolhand.services.ServiceConfig;
import com.coolhandlabs.coolhand.services.StepTemplateService;
import com.coolhandlabs.coolhand.services.TemplateService;
import com.coolhandlabs.coolhand.services.WorkloadService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Coolhand client: logs LLM calls captured by the integrations (see
 * {@code com.coolhandlabs.coolhand.integrations}), submits feedback and manual logs, declares
 * workloads with {@link #withContext}, and reads logs, feedback, templates, workloads and more back.
 *
 * <pre>{@code
 * Coolhand coolhand = new Coolhand(CoolhandOptions.builder().apiKey(System.getenv("COOLHAND_API_KEY")).build());
 * HttpClient http = CoolhandHttpClient.wrap(HttpClient.newHttpClient(), coolhand);
 * }</pre>
 *
 * <p>Error semantics follow coolhand-node: {@link #createFeedback}, {@link #logRequest} and
 * {@link #uploadClientFile} never throw for API or network failures and return an empty
 * {@code Optional} instead; read and admin methods throw {@link CoolhandException} (network, bad
 * JSON) or {@link CoolhandHttpException} (non-2xx, with {@code status()}); invalid arguments throw
 * {@link IllegalArgumentException} before any request. A client is thread-safe; {@link #close()}
 * waits briefly for logs still being submitted.
 */
public final class Coolhand implements AutoCloseable {
    /** The default {@code excludeApiPatterns}: batch, file, fine-tuning and model-listing control-plane paths. */
    public static final List<String> DEFAULT_EXCLUDE_API_PATTERNS = List.of(
            "/batchPredictionJobs/",
            "/openai/files", "/openai/v1/files",
            "/openai/batches", "/openai/v1/batches",
            "/openai/fine_tuning", "/openai/v1/fine_tuning",
            "/openai/models", "/openai/v1/models");

    private final boolean silent;
    private final PatternMatchingService patternMatchingService;
    private final LoggingService loggingService;
    private final FeedbackService feedbackService;
    private final TemplateService templateService;
    private final StepTemplateService stepTemplateService;
    private final WorkloadService workloadService;
    private final ClientFileService clientFileService;
    private final LlmReferenceService llmReferenceService;
    private final HelpCenterDocsService helpCenterDocsService;
    private final OptimizationFeedbackLinkService optimizationFeedbackLinkService;
    private final RequestMonitor requestMonitor;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    /**
     * Creates a client.
     *
     * @throws IllegalArgumentException if the API key is missing or not a valid header value, the base
     *     URL is invalid, or the timeout is out of range
     */
    public Coolhand(CoolhandOptions options) {
        this.silent = options.silent();
        if (!options.hasApiKey()) {
            Log.error("❌ API key is required for logging. Pass it in CoolhandOptions.apiKey");
            throw new IllegalArgumentException("API key is required");
        }
        Log log = new Log(silent);
        this.patternMatchingService = new PatternMatchingService(options.patternsFile(), silent);
        ServiceConfig config = new ServiceConfig(options.apiKey(), silent, options.debug(), options.dryRun(),
                options.baseUrl(), options.requestTimeout(), options.httpClient());
        this.loggingService = new LoggingService(config);
        this.feedbackService = new FeedbackService(config);
        this.templateService = new TemplateService(config);
        this.stepTemplateService = new StepTemplateService(config);
        this.workloadService = new WorkloadService(config);
        this.clientFileService = new ClientFileService(config);
        this.llmReferenceService = new LlmReferenceService(config);
        this.helpCenterDocsService = new HelpCenterDocsService(config);
        this.optimizationFeedbackLinkService = new OptimizationFeedbackLinkService(config);
        UrlFilters.warnOnGlobLikePatterns(options.excludeApiPatterns());
        List<String> exclude = options.excludeApiPatterns() != null ? options.excludeApiPatterns() : DEFAULT_EXCLUDE_API_PATTERNS;
        this.requestMonitor = new RequestMonitor(patternMatchingService, exclude, loggingService.getApiEndpoint(), silent,
                options.captureIdleTimeout(), this::submitCaptured);

        log.info("🔍 Setting up Coolhand...");
        if (options.dryRun()) {
            log.info("🚫 DRY RUN MODE: API calls will be skipped — no data will be submitted");
        }
        if (options.debug()) {
            log.info("🔬 DEBUG MODE: Verbose logging enabled");
        }
        log.info("🎯 API Endpoint: " + loggingService.getApiEndpoint());
        log.info("✅ Coolhand ready - will log to API");
    }

    private void submitCaptured(CallData callData, MatchedPattern matched, DeclaredContext declared) {
        track(loggingService.logRequestToApi(callData, matched, CollectionMethod.GLOBAL_MONITORING, null, null, declared));
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((r, e) -> pending.remove(future));
        return future;
    }

    // ------------------------------------------------------------------ declared context

    /**
     * Runs {@code body} with {@code ctx} declared for every LLM call captured on this thread while it
     * runs (sent as {@code llm_request_log.declared}), and returns its result. Nests: per field the
     * innermost value wins, workload and template are replaced wholesale, and a context that sets a
     * workload without a template does not inherit the outer template. Exceptions from {@code body}
     * propagate unchanged.
     *
     * <p>The context is thread-scoped: work handed to other threads carries it only when wrapped with
     * {@link #wrapWithContext(Runnable)} (integrations snapshot it on the thread that sends the request).
     *
     * @throws IllegalArgumentException before {@code body} runs, for a malformed declaration (blank or
     *     invalid key, over-long description, a template key with no workload in scope)
     */
    public static <T, E extends Exception> T withContext(DeclaredContext ctx, ContextCall<T, E> body) throws E {
        if (body == null) {
            throw new IllegalArgumentException("withContext: second argument must be a function");
        }
        DeclaredContext merged = DeclaredContexts.merge(DeclaredContexts.installed(), ctx);
        DeclaredContext previous = DeclaredContexts.install(merged);
        try {
            return body.call();
        } finally {
            DeclaredContexts.restore(previous);
        }
    }

    /** {@link #withContext(DeclaredContext, ContextCall)} for a block with no result. */
    public static <E extends Exception> void withContext(DeclaredContext ctx, ContextRun<E> body) throws E {
        if (body == null) {
            throw new IllegalArgumentException("withContext: second argument must be a function");
        }
        withContext(ctx, () -> {
            body.run();
            return null;
        });
    }

    /** The declaration active on this thread, if any (an empty declaration counts as none). */
    public static Optional<DeclaredContext> currentContext() {
        return DeclaredContexts.current();
    }

    /** {@code task} bound to the declared context active now, for running on another thread. */
    public static Runnable wrapWithContext(Runnable task) {
        DeclaredContext captured = DeclaredContexts.installed();
        return () -> {
            DeclaredContext previous = DeclaredContexts.install(captured);
            try {
                task.run();
            } finally {
                DeclaredContexts.restore(previous);
            }
        };
    }

    /** {@code task} bound to the declared context active now, for running on another thread. */
    public static <T> Callable<T> wrapWithContext(Callable<T> task) {
        DeclaredContext captured = DeclaredContexts.installed();
        return () -> {
            DeclaredContext previous = DeclaredContexts.install(captured);
            try {
                return task.call();
            } finally {
                DeclaredContexts.restore(previous);
            }
        };
    }

    // ------------------------------------------------------------------ global monitoring

    private static final Object GLOBAL_LOCK = new Object();
    private static volatile Coolhand global;
    private static volatile boolean envInitAttempted;

    /**
     * Installs the process-wide client the integrations use when none is passed to them (coolhand-node's
     * {@code initializeGlobalMonitoring}). A second call while one is active returns the active one.
     */
    public static Coolhand initializeGlobalMonitoring(CoolhandOptions options) {
        synchronized (GLOBAL_LOCK) {
            if (global != null) {
                return global;
            }
            Coolhand created = new Coolhand(options);
            global = created;
            if (!created.silent) {
                new Log(false).info("🌐 Global Coolhand monitoring initialized");
            }
            return created;
        }
    }

    /**
     * The process-wide client: the one installed by {@link #initializeGlobalMonitoring}, otherwise one
     * created once from the environment ({@link CoolhandOptions#fromEnv()}) when {@code COOLHAND_API_KEY}
     * is set, as coolhand-node's {@code auto-monitor} does. Empty when there is neither, or when the
     * environment configuration is invalid (logged).
     */
    public static Optional<Coolhand> global() {
        Coolhand current = global;
        if (current != null || envInitAttempted) {
            return Optional.ofNullable(current);
        }
        synchronized (GLOBAL_LOCK) {
            if (global == null && !envInitAttempted) {
                envInitAttempted = true;
                CoolhandOptions options = CoolhandOptions.fromEnv();
                if (options.hasApiKey()) {
                    try {
                        global = new Coolhand(options);
                    } catch (RuntimeException e) {
                        Log.error("❌ Failed to initialize global monitoring: " + e.getMessage());
                    }
                }
            }
            return Optional.ofNullable(global);
        }
    }

    /**
     * True when Coolhand can log: a global client is installed, or {@code COOLHAND_API_KEY} is set
     * (non-blank) in the environment.
     */
    public static boolean isConfigured() {
        return global != null || CoolhandOptions.fromEnv().hasApiKey();
    }

    /** True once a global client exists. */
    public static boolean isGlobalMonitoringActive() {
        return global != null;
    }

    /** Statistics of the global client, or zeros with {@code apiEndpoint} {@code "Not initialized"}. */
    public static CoolhandStats getGlobalStats() {
        Coolhand current = global;
        return current != null ? current.getStats() : new CoolhandStats(0, 0, "Not initialized");
    }

    /** Drops the global client (and lets the environment be read again). For tests. */
    public static void resetGlobalMonitoring() {
        synchronized (GLOBAL_LOCK) {
            Coolhand current = global;
            global = null;
            envInitAttempted = false;
            if (current != null) {
                current.flush(Duration.ofSeconds(5));
            }
        }
    }

    // ------------------------------------------------------------------ logs

    /**
     * Submits one captured request/response that did not flow through an integration (e.g. replayed
     * sessions). The declared context active on this thread is sent with it. Never throws for API or
     * network failures.
     *
     * @return the created log, or empty on failure or a dry run
     */
    public Optional<LogResponse> logRequest(CallData rawRequest) {
        return BaseService.await(logRequestAsync(rawRequest, null, null));
    }

    /**
     * {@link #logRequest(CallData)} with an explicit {@code collector} (overrides the SDK collector
     * string; may be null) and free-form {@code metadata} (convention: {@code project_path}; may be null).
     */
    public Optional<LogResponse> logRequest(CallData rawRequest, String collector, Map<String, ?> metadata) {
        return BaseService.await(logRequestAsync(rawRequest, collector, metadata));
    }

    public CompletableFuture<Optional<LogResponse>> logRequestAsync(CallData rawRequest, String collector,
            Map<String, ?> metadata) {
        return track(loggingService.logRequestToApi(rawRequest, null, CollectionMethod.MANUAL, collector, metadata,
                DeclaredContexts.current().orElse(null)));
    }

    /**
     * Full input/output content of one log by id (private API key).
     *
     * @throws IllegalArgumentException if {@code logId} is blank or {@code .}/{@code ..}
     */
    public JsonNode getLogContent(String logId) {
        return loggingService.getLogContent(logId, GetLogContentOptions.NONE);
    }

    public JsonNode getLogContent(String logId, GetLogContentOptions options) {
        return loggingService.getLogContent(logId, options);
    }

    /** Searches logs by named filters (private API key). */
    public Page<JsonNode> searchLogs(SearchLogsParams params) {
        return loggingService.searchLogs(params);
    }

    public Page<JsonNode> searchLogs() {
        return searchLogs(SearchLogsParams.NONE);
    }

    // ------------------------------------------------------------------ feedback

    /**
     * Creates feedback for an LLM output. Never throws for API or network failures.
     *
     * @return the created record (including {@code llmRequestLogId} when it matched a log), or empty on
     *     any failure or a dry run
     */
    public Optional<FeedbackResponse> createFeedback(LlmRequestLogFeedback feedback) {
        return BaseService.await(createFeedbackAsync(feedback));
    }

    public CompletableFuture<Optional<FeedbackResponse>> createFeedbackAsync(LlmRequestLogFeedback feedback) {
        return track(feedbackService.createFeedbackAsync(feedback, CollectionMethod.MANUAL));
    }

    /** Searches feedback with raw Ransack predicates (private API key). */
    public Page<JsonNode> searchFeedback(SearchFeedbackParams params) {
        return feedbackService.searchFeedback(params);
    }

    /** One feedback record with partials (private API key). */
    public JsonNode getFeedback(String id) {
        return feedbackService.getFeedback(id);
    }

    // ------------------------------------------------------------------ templates, workloads

    public Page<JsonNode> searchTemplates(SearchTemplatesParams params) {
        return templateService.searchTemplates(params);
    }

    public JsonNode getTemplate(String id) {
        return templateService.getTemplate(id, MetricsWindow.NONE);
    }

    public JsonNode getTemplate(String id, MetricsWindow metrics) {
        return templateService.getTemplate(id, metrics);
    }

    public Page<JsonNode> listStepTemplates(ListStepTemplatesParams params) {
        return stepTemplateService.listStepTemplates(params);
    }

    public JsonNode getStepTemplate(long id) {
        return stepTemplateService.getStepTemplate(id, null);
    }

    public JsonNode getStepTemplate(long id, boolean includeMetrics) {
        return stepTemplateService.getStepTemplate(id, includeMetrics);
    }

    public Page<JsonNode> searchWorkloads(SearchWorkloadsParams params) {
        return workloadService.searchWorkloads(params);
    }

    /** Creates or updates a workload by key (private API key); empty on a dry run. */
    public Optional<UpsertResult> upsertWorkload(UpsertWorkloadParams params) {
        return workloadService.upsert(params);
    }

    /** Creates or updates a template by key (private API key); empty on a dry run. */
    public Optional<UpsertResult> upsertTemplate(UpsertTemplateParams params) {
        return templateService.upsert(params);
    }

    // ------------------------------------------------------------------ referenced files

    public Page<JsonNode> searchReferencedFiles(SearchReferencedFilesParams params) {
        return llmReferenceService.searchReferencedFiles(params);
    }

    public Page<JsonNode> listReferencedFileSessions(ListReferencedFileSessionsParams params) {
        return llmReferenceService.listReferencedFileSessions(params);
    }

    // ------------------------------------------------------------------ client files

    /** Uploads a file as a draft (private API key). Never throws for API or network failures. */
    public Optional<ClientFile> uploadClientFile(ClientFileUpload payload) {
        return clientFileService.createClientFile(payload);
    }

    public Page<ClientFile> listClientFiles(ListClientFilesParams params) {
        return clientFileService.listClientFiles(params);
    }

    public DownloadedClientFile downloadClientFile(String id) {
        return clientFileService.downloadClientFile(id);
    }

    public Optional<ClientFile> updateClientFileStatus(String id, String status) {
        return clientFileService.updateClientFileStatus(id, status);
    }

    // ------------------------------------------------------------------ optimization links

    public Optional<OptimizationFeedbackLink> linkFeedback(String optimizationId, String feedbackId) {
        return optimizationFeedbackLinkService.linkFeedback(optimizationId, feedbackId);
    }

    public Optional<OptimizationFeedbackLink> linkFeedback(String optimizationId, String feedbackId, String note) {
        return optimizationFeedbackLinkService.linkFeedback(optimizationId, feedbackId, note);
    }

    public Optional<BulkLinkFeedbackResult> bulkLinkFeedback(String optimizationId, List<String> feedbackIds) {
        return optimizationFeedbackLinkService.bulkLinkFeedback(optimizationId, feedbackIds);
    }

    public Optional<BulkLinkFeedbackResult> bulkLinkFeedback(String optimizationId, List<String> feedbackIds, String note) {
        return optimizationFeedbackLinkService.bulkLinkFeedback(optimizationId, feedbackIds, note);
    }

    public void unlinkFeedback(String optimizationId, String linkId) {
        optimizationFeedbackLinkService.unlinkFeedback(optimizationId, linkId);
    }

    // ------------------------------------------------------------------ help center

    public Page<JsonNode> searchHelpArticles(SearchHelpArticlesParams params) {
        return helpCenterDocsService.searchHelpArticles(params);
    }

    public JsonNode getHelpArticle(String slug) {
        return helpCenterDocsService.getHelpArticle(slug);
    }

    public List<JsonNode> listHelpCategories() {
        return helpCenterDocsService.listHelpCategories();
    }

    // ------------------------------------------------------------------ monitoring

    /** Headers as they would be logged (credentials redacted). */
    public Map<String, Object> sanitizeHeaders(Map<String, ?> headers, ApiPattern pattern) {
        return patternMatchingService.sanitizeHeaders(headers, pattern);
    }

    public CoolhandStats getStats() {
        return new CoolhandStats(requestMonitor.totalRequests(), requestMonitor.interceptedCalls(),
                loggingService.getApiEndpoint());
    }

    public List<String> getExcludeApiPatterns() {
        return requestMonitor.getExcludeApiPatterns();
    }

    /** Replaces the URL substrings never captured (warns about glob-like entries). */
    public void setExcludeApiPatterns(List<String> patterns) {
        requestMonitor.setExcludeApiPatterns(patterns);
    }

    /** The capture engine the integrations feed; see {@link RequestMonitor} to write your own. */
    public RequestMonitor monitor() {
        return requestMonitor;
    }

    /**
     * Waits until every captured call whose body has ended, and every submission started so far, has
     * been handed to the Coolhand API (or failed), up to {@code timeout}.
     *
     * @return true if nothing was left pending
     */
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (requestMonitor.pendingFinalizations() == 0 && pending.isEmpty()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** {@link #flush(Duration)} with five seconds. The client stays usable. */
    @Override
    public void close() {
        flush(Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ services

    public PatternMatchingService patternMatchingService() { return patternMatchingService; }
    public LoggingService loggingService() { return loggingService; }
    public FeedbackService feedbackService() { return feedbackService; }
    public TemplateService templateService() { return templateService; }
    public StepTemplateService stepTemplateService() { return stepTemplateService; }
    public WorkloadService workloadService() { return workloadService; }
    public ClientFileService clientFileService() { return clientFileService; }
    public LlmReferenceService llmReferenceService() { return llmReferenceService; }
    public HelpCenterDocsService helpCenterDocsService() { return helpCenterDocsService; }
    public OptimizationFeedbackLinkService optimizationFeedbackLinkService() { return optimizationFeedbackLinkService; }
}
