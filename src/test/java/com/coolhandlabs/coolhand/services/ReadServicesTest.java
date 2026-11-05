package com.coolhandlabs.coolhand.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.CoolhandException;
import com.coolhandlabs.coolhand.CoolhandHttpException;
import com.coolhandlabs.coolhand.model.BulkLinkFeedbackResult;
import com.coolhandlabs.coolhand.model.ClientFile;
import com.coolhandlabs.coolhand.model.ClientFileUpload;
import com.coolhandlabs.coolhand.model.DownloadedClientFile;
import com.coolhandlabs.coolhand.model.ListClientFilesParams;
import com.coolhandlabs.coolhand.model.ListReferencedFileSessionsParams;
import com.coolhandlabs.coolhand.model.ListStepTemplatesParams;
import com.coolhandlabs.coolhand.model.MetricsWindow;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchHelpArticlesParams;
import com.coolhandlabs.coolhand.model.SearchReferencedFilesParams;
import com.coolhandlabs.coolhand.model.SearchTemplatesParams;
import com.coolhandlabs.coolhand.model.SearchWorkloadsParams;
import com.coolhandlabs.coolhand.model.UpsertResult;
import com.coolhandlabs.coolhand.model.UpsertTemplateParams;
import com.coolhandlabs.coolhand.model.UpsertWorkloadParams;
import com.coolhandlabs.coolhand.support.FakeServer;
import com.coolhandlabs.coolhand.support.TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReadServicesTest {
    private final FakeServer api = new FakeServer();

    @AfterEach
    void close() {
        api.close();
    }

    private ServiceConfig config() {
        return ServiceConfig.builder().apiKey(TestSupport.API_KEY).baseUrl(api.baseUrl()).build();
    }

    private ServiceConfig dryRun() {
        return ServiceConfig.builder().apiKey(TestSupport.API_KEY).baseUrl(api.baseUrl()).dryRun(true).build();
    }

    @Test
    void templatesSearchGetAndUpsert() {
        api.on("/api/v2/llm_request_templates", FakeServer.json(200, "[{\"id\":\"t1\"}]"));
        TemplateService s = new TemplateService(config());
        Page<JsonNode> page = s.searchTemplates(SearchTemplatesParams.builder().search("parse").workloadId("w")
                .status("published").includeDeprecated(false).includeSystem(true)
                .metrics(MetricsWindow.include(true).withDaysBack(7)).page(1).per(10).build());
        assertEquals("/api/v2/llm_request_templates?search=parse&workload_id=w&status=published&include_deprecated=false"
                + "&include_system=true&include_metrics=true&days_back=7&page=1&per=10", api.take().target());
        assertEquals("t1", page.items().get(0).get("id").asText());

        api.on("/api/v2/llm_request_templates/", FakeServer.json(200, "{\"id\":\"t1\",\"user_prompt_pattern\":\"x\"}"));
        s.getTemplate("t1", MetricsWindow.include(false));
        assertEquals("/api/v2/llm_request_templates/t1?include_metrics=false", api.take().target());

        api.on("/api/v2/llm_request_templates/by_key/", FakeServer.json(201, "{\"id\":\"t9\",\"name\":\"N\"}"));
        Optional<UpsertResult> up = s.upsert(UpsertTemplateParams.key("Parser#call/squish:L2").withWorkloadKey("Parser#call")
                .withName("N"));
        FakeServer.Received put = api.take();
        assertEquals("PUT", put.method());
        assertEquals("/api/v2/llm_request_templates/by_key/Parser%23call/squish%3AL2", put.path());
        assertEquals(TestSupport.json("{\"name\":\"N\",\"workload_key\":\"Parser#call\"}"), put.json());
        assertTrue(up.orElseThrow().created());
        assertEquals("t9", up.orElseThrow().record().get("id").asText());

        assertThrows(IllegalArgumentException.class, () -> s.upsert(UpsertTemplateParams.key("a/./b")));
        assertThrows(IllegalArgumentException.class, () -> s.upsert(UpsertTemplateParams.key("/a")));
        assertThrows(IllegalArgumentException.class, () -> s.upsert(UpsertTemplateParams.key("k").withWorkloadId(" ")));
        assertTrue(new TemplateService(dryRun()).upsert(UpsertTemplateParams.key("k")).isEmpty());
        assertNull(api.poll(Duration.ofMillis(200)));
    }

    @Test
    void workloadsSearchAndUpsert() {
        api.on("/api/v2/workloads", FakeServer.json(200, "[]"));
        WorkloadService s = new WorkloadService(config());
        Page<JsonNode> page = s.searchWorkloads(SearchWorkloadsParams.builder().includeTemplates(true)
                .metrics(MetricsWindow.include(true).between("2026-01-01", "2026-02-01")).build());
        assertEquals("/api/v2/workloads?include_templates=true&include_metrics=true&since=2026-01-01&until=2026-02-01",
                api.take().target());
        assertEquals(0, page.pagination().totalCount());

        api.on("/api/v2/workloads/by_key/", FakeServer.json(200, "{\"id\":\"w1\",\"name\":\"Triage\"}"));
        UpsertResult up = s.upsert(UpsertWorkloadParams.key("support/triage").withName("Triage").withDescription("d").withReporter("r"))
                .orElseThrow();
        FakeServer.Received put = api.take();
        assertEquals("/api/v2/workloads/by_key/support/triage", put.path());
        assertEquals(TestSupport.json("{\"name\":\"Triage\",\"description\":\"d\",\"reporter\":\"r\"}"), put.json());
        assertEquals(false, up.created());
        assertThrows(IllegalArgumentException.class,
                () -> s.upsert(UpsertWorkloadParams.key("k").withDescription("x".repeat(1001))));
    }

    @Test
    void stepTemplates() {
        api.on("/api/v2/step_templates", FakeServer.json(200, "[{\"id\":3}]"));
        StepTemplateService s = new StepTemplateService(config());
        s.listStepTemplates(new ListStepTemplatesParams("lint", "tool_step", true, null, 5));
        assertEquals("/api/v2/step_templates?search=lint&step_type=tool_step&include_metrics=true&per=5", api.take().target());
        api.on("/api/v2/step_templates/", FakeServer.json(200, "{\"id\":3}"));
        assertEquals(3, s.getStepTemplate(3, false).get("id").intValue());
        assertEquals("/api/v2/step_templates/3?include_metrics=false", api.take().target());
        assertThrows(IllegalArgumentException.class, () -> s.getStepTemplate(0, null));
    }

    @Test
    void referencedFiles() {
        api.on("/api/v2/llm_references", FakeServer.json(200, "[{\"file_path\":\"a.rb\",\"reference_count\":3}]"));
        LlmReferenceService s = new LlmReferenceService(config());
        s.searchReferencedFiles(new SearchReferencedFilesParams("app/", "2026-01-01", null, 2, null));
        assertEquals("/api/v2/llm_references?q%5Bfile_path_cont%5D=app%2F&q%5Bcreated_at_gteq%5D=2026-01-01&page=2",
                api.take().target());
        s.listReferencedFileSessions(ListReferencedFileSessionsParams.of("app/models/user.rb"));
        assertEquals("/api/v2/llm_references/sessions?file_path=app%2Fmodels%2Fuser.rb", api.take().target());
        assertThrows(IllegalArgumentException.class, () -> s.listReferencedFileSessions(ListReferencedFileSessionsParams.of(null)));
    }

    @Test
    void helpCenterIsReadableWithoutAKey() {
        api.on("/api/v2/public_content", FakeServer.json(200, "[{\"slug\":\"a\"}]"));
        HelpCenterDocsService anonymous = new HelpCenterDocsService(ServiceConfig.builder().apiKey("").baseUrl(api.baseUrl()).build());
        anonymous.searchHelpArticles(new SearchHelpArticlesParams(" logs ", " guides ", null, 5));
        FakeServer.Received r = api.take();
        assertEquals("/api/v2/public_content?q%5Bhelp_category_slug_eq%5D=guides&q%5Bfull_text%5D=logs&per_page=5", r.target());
        assertNull(r.header("X-API-Key"));

        new HelpCenterDocsService(config()).searchHelpArticles(SearchHelpArticlesParams.query("feedback"));
        FakeServer.Received keyed = api.take();
        assertEquals("/api/v2/public_content?q=feedback", keyed.target());
        assertEquals(TestSupport.API_KEY, keyed.header("X-API-Key"));

        api.on("/api/v2/public_content/categories", FakeServer.json(200, "[{\"slug\":\"guides\"}]"));
        assertEquals("guides", anonymous.listHelpCategories().get(0).get("slug").asText());
        api.take();
        api.on("/api/v2/public_content/", FakeServer.json(200, "{\"slug\":\"x\",\"body\":\"# X\"}"));
        assertEquals("# X", anonymous.getHelpArticle("x").get("body").asText());
    }

    @Test
    void mcpCallsAToolAndSurfacesErrors() {
        api.on("/mcp", FakeServer.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}"));
        McpService s = new McpService(config());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "q");
        assertTrue(s.mcpCall("search", args).orElseThrow().has("content"));
        assertEquals(TestSupport.json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search\","
                + "\"arguments\":{\"query\":\"q\"}}}"), api.take().json());

        api.on("/mcp", FakeServer.json(200, "{\"error\":{\"message\":\"bad key " + TestSupport.API_KEY + "\"}}"));
        assertEquals("MCP error: bad key [redacted]", assertThrows(CoolhandException.class, () -> s.mcpCall("x", Map.of())).getMessage());
        api.on("/mcp", FakeServer.json(200, "{\"result\":{\"error\":\"tool failed\"}}"));
        assertEquals("tool failed", assertThrows(CoolhandException.class, () -> s.mcpCall("x", Map.of())).getMessage());
        assertTrue(new McpService(dryRun()).mcpCall("x", Map.of()).isEmpty());
    }

    @Test
    void clientFilesUploadListDownloadAndStatus() {
        api.on("/api/v2/client_files", FakeServer.json(201, "{\"id\":\"cf1\",\"name\":\"Deck\",\"status\":\"draft\"}"));
        ClientFileService s = new ClientFileService(config());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("quarter", "Q3");
        metadata.put("pages", 12);
        ClientFile created = s.createClientFile(new ClientFileUpload("Deck", "slide_deck", null,
                "PDFBYTES".getBytes(StandardCharsets.UTF_8), "deck.pdf", metadata)).orElseThrow();
        assertEquals("cf1", created.id());
        FakeServer.Received upload = api.take();
        assertTrue(upload.header("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = upload.text();
        assertTrue(body.contains("name=\"client_file[name]\"\r\n\r\nDeck\r\n"));
        assertTrue(body.contains("name=\"client_file[file_type]\"\r\n\r\nslide_deck\r\n"));
        assertTrue(body.contains("name=\"client_file[file]\"; filename=\"deck.pdf\"\r\nContent-Type: application/octet-stream\r\n\r\nPDFBYTES\r\n"));
        assertTrue(body.contains("name=\"client_file[metadata][pages]\"\r\n\r\n12\r\n"));
        assertThrows(IllegalArgumentException.class, () -> s.createClientFile(new ClientFileUpload("x", null, null,
                new byte[0], "f", Map.of("a[b]", "c"))));

        api.on("/api/v2/client_files", FakeServer.json(200, "[{\"id\":\"cf1\",\"status\":\"published\"}]", Map.of("X-Total-Count", "1")));
        Page<ClientFile> page = s.listClientFiles(new ListClientFilesParams("published", "report", null, null));
        assertEquals("/api/v2/client_files?status=published&file_type=report", api.take().target());
        assertEquals("published", page.items().get(0).status());

        api.on("/api/v2/client_files/cf1/content", FakeServer.raw(200, "application/pdf", new byte[] {1, 2, 3},
                Map.of("Content-Disposition", "attachment; filename=\"q3 deck.pdf\"; filename*=UTF-8''q3%20deck%E2%9C%93.pdf")));
        DownloadedClientFile file = s.downloadClientFile("cf1");
        assertArrayEquals(new byte[] {1, 2, 3}, file.content());
        assertEquals(Optional.of("application/pdf"), file.contentType());
        assertEquals(Optional.of("q3 deck✓.pdf"), file.filename());
        api.take();

        api.on("/api/v2/client_files/cf1", FakeServer.json(200, "{\"id\":\"cf1\",\"status\":\"archived\"}"));
        assertEquals("archived", s.updateClientFileStatus("cf1", "archived").orElseThrow().status());
        FakeServer.Received patch = api.take();
        assertEquals("PATCH", patch.method());
        assertEquals(TestSupport.json("{\"client_file\":{\"status\":\"archived\"}}"), patch.json());
        api.on("/api/v2/client_files/cf1", FakeServer.json(403, "{}"));
        assertEquals(403, assertThrows(CoolhandHttpException.class, () -> s.updateClientFileStatus("cf1", "draft")).status());
        assertEquals("plain.txt", ClientFileService.filenameFromContentDisposition("attachment; filename=plain.txt"));
    }

    @Test
    void feedbackLinksSingleBulkAndUnlink() {
        api.on("/api/v2/optimizations/", FakeServer.json(201, "{\"id\":\"l1\",\"optimization_id\":\"o1\",\"feedback_id\":\"f1\",\"note\":null}"));
        OptimizationFeedbackLinkService s = new OptimizationFeedbackLinkService(config());
        assertEquals("l1", s.linkFeedback("o1", "f1").orElseThrow().id());
        FakeServer.Received link = api.take();
        assertEquals("/api/v2/optimizations/o1/feedback_links", link.path());
        assertEquals(TestSupport.json("{\"feedback_id\":\"f1\"}"), link.json());
        s.linkFeedback("o1", "f1", null);
        assertEquals(TestSupport.json("{\"feedback_id\":\"f1\",\"note\":null}"), api.take().json());

        api.on("/api/v2/optimizations/", FakeServer.json(200, "{\"linked\":100,\"already_linked\":0,\"errored\":0,\"not_found\":[]}"));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add("f" + i);
        }
        BulkLinkFeedbackResult bulk = s.bulkLinkFeedback("o1", ids, "evidence").orElseThrow();
        assertEquals(200, bulk.linked());
        assertEquals(100, api.take().json().get("feedback_ids").size());
        JsonNode second = api.take().json();
        assertEquals(50, second.get("feedback_ids").size());
        assertEquals("evidence", second.get("note").asText());

        api.on("/api/v2/optimizations/", FakeServer.json(200, "{\"linked\":\"x\"}"));
        assertThrows(CoolhandException.class, () -> s.bulkLinkFeedback("o1", List.of("a")));
        api.take();

        api.on("/api/v2/optimizations/", FakeServer.json(204, ""));
        s.unlinkFeedback("o1", "l 1");
        FakeServer.Received del = api.take();
        assertEquals("DELETE", del.method());
        assertEquals("/api/v2/optimizations/o1/feedback_links/l%201", del.path());

        assertThrows(IllegalArgumentException.class, () -> s.linkFeedback("..", "f"));
        assertThrows(IllegalArgumentException.class, () -> s.bulkLinkFeedback("o", List.of()));
        assertTrue(new OptimizationFeedbackLinkService(dryRun()).linkFeedback("o", "f").isEmpty());
    }
}
