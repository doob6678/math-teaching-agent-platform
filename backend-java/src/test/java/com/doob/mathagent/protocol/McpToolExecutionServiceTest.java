package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.vo.McpReactToolPlan;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.teacher.TeacherResourceServiceFixture;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceAssetStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.service.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolExecutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void registeredMcpSecretCallsTextbookEvidenceToolWithBackendClientIdentity() throws Exception {
        Path root = textbookCorpus();
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithTeacherTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(root));

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "search_textbook_evidence",
                new McpToolCallRequest(Map.of("query", "space vector angle", "limit", 3)));

        assertThat(response.toolName()).isEqualTo("search_textbook_evidence");
        assertThat(response.clientId()).isEqualTo("workbuddy-teacher");
        assertThat(response.subjectType()).isEqualTo("teacher");
        assertThat(response.result()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("query")).isEqualTo("space vector angle");
        assertThat(result.get("total")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<TextbookSearchHit> hits = (List<TextbookSearchHit>) result.get("hits");
        assertThat(hits)
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.docId()).isEqualTo("book_vector");
                    assertThat(hit.pageImageUri()).isEqualTo("/api/resources/textbooks/book_vector/pages/1/image");
                });
    }

    @Test
    void rejectsWrongSecretAndToolsNotGrantedToClient() throws Exception {
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithTeacherTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()));

        assertThatThrownBy(() -> service.callTool(
                        "Bearer bad_secret_1234567890abcdef",
                        "search_textbook_evidence",
                        new McpToolCallRequest(Map.of("query", "vector"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP secret");

        assertThatThrownBy(() -> service.callTool(
                        "Bearer teacher_secret_1234567890abcdef",
                        "export_handout_pdf",
                        new McpToolCallRequest(Map.of("taskId", "task-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void teacherMcpSecretCallsTeacherResourceEvidenceWithoutLeakingOtherTeacherPrivateBlocks() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        resourceStore.save(document("doc-own", "workbuddy-teacher-subject", "TEACHER_PRIVATE", "Own Feishu vector notes"));
        resourceStore.save(document("doc-other", "teacher-2", "TEACHER_PRIVATE", "Other private vector notes"));
        TeacherResourceAssetService assetService = new TeacherResourceAssetService(
                assetStore,
                resourceStore,
                new TeacherSourceSyncProperties("", tempDir.resolve("download.py"), tempDir.resolve("APPKEY.md"), tempDir.resolve("staging"), 1));
        TeacherResourceAssetResponse asset = assetService.saveExtractedAsset(
                resourceStore.find("default", "doc-own"),
                "diagram-note.docx",
                null,
                "/word/media/image1.png",
                new byte[] {1, 2, 3},
                "image/png").orElseThrow();
        blockStore.replaceActiveBlocks("default", "doc-own", List.of(blockWithImageRef(
                "b-own",
                "doc-own",
                "Feishu teacher method explains space vector angle with normal vectors.",
                asset.assetId())));
        blockStore.replaceActiveBlocks("default", "doc-other", List.of(block(
                "b-other",
                "doc-other",
                "Another teacher private Feishu method must not leak.")));
        TeacherResourceBlockSearchService searchService = new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                event -> { },
                TestVectorIndexService.successful(resourceStore, blockStore),
                TeacherResourceGraphAlignmentService.disabled(),
                assetService);
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithTeacherResourceTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                searchService);

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "search_teacher_resource_evidence",
                new McpToolCallRequest(Map.of("query", "normal vectors", "limit", 5)));

        assertThat(response.toolName()).isEqualTo("search_teacher_resource_evidence");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("hitCount")).isEqualTo(1);
        assertThat(result.toString()).contains("b-own");
        assertThat(result.toString()).doesNotContain("b-other");
        @SuppressWarnings("unchecked")
        List<com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse.Hit> hits =
                (List<com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse.Hit>) result.get("hits");
        assertThat(hits)
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.imageAssetIds()).containsExactly(asset.assetId());
                    assertThat(hit.assetRefs()).singleElement().satisfies(assetRef ->
                            assertThat(assetRef.assetUri()).isEqualTo("/api/teacher/resources/assets/" + asset.assetId()));
                });
    }

    @Test
    void teacherMcpSecretCanConstrainTeacherResourceEvidenceByLibraryAlias() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-qq",
                "default",
                "workbuddy-teacher-subject",
                "local_path",
                "Runtime QQ bundle package",
                null,
                "C:/workspace/runtime-authored/02-qq-bundle-vector",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-feishu",
                "default",
                "workbuddy-teacher-subject",
                "local_path",
                "Runtime Feishu method package",
                null,
                "C:/workspace/runtime-authored/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("default", "doc-qq", List.of(block(
                "b-qq",
                "doc-qq",
                "QQ bundle analysis explains the vector angle route.")));
        blockStore.replaceActiveBlocks("default", "doc-feishu", List.of(block(
                "b-feishu",
                "doc-feishu",
                "Feishu method reminds students to separate model choices.")));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithTeacherResourceTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore));

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "search_teacher_resource_evidence",
                new McpToolCallRequest(Map.of(
                        "query", "vector angle route",
                        "limit", 5,
                        "library", "qq_bundle")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("hitCount")).isEqualTo(1);
        assertThat(result.toString()).contains("b-qq");
        assertThat(result.toString()).doesNotContain("b-feishu");
    }

    @Test
    void studentMcpSecretCannotCallTeacherResourceEvidenceEvenIfMisconfigured() throws Exception {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-student",
                "student",
                "default",
                "student-mcp-client",
                McpClientRegistryProperties.secretHash("student_secret_1234567890abcdef"),
                true,
                List.of("search_teacher_resource_evidence"),
                List.of("teacher-resource:read", "MATH_VIP"))));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                properties,
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(
                        new InMemoryTeacherResourceStore(),
                        new InMemoryTeacherDocumentBlockStore()));

        assertThatThrownBy(() -> service.callTool(
                        "Bearer student_secret_1234567890abcdef",
                        "search_teacher_resource_evidence",
                        new McpToolCallRequest(Map.of("query", "vector"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }

    @Test
    void mcpToolRequiresRegistryScopeBeyondToolAllowList() throws Exception {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("download_feishu_resource"),
                List.of("teacher-resource:read"))));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                properties,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.callTool(
                        "Bearer teacher_secret_1234567890abcdef",
                        "download_feishu_resource",
                        new McpToolCallRequest(Map.of("url", "https://my.feishu.cn/docx/doc-token"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher-resource:sync-execute");
    }

    @Test
    void mcpSecretReadsOnlyOwnedTeachingAiTraceByTaskId() throws Exception {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        traceStore.save(trace("trace-own", "task-own", "teacher", "workbuddy-teacher-subject"));
        traceStore.save(trace("trace-other", "task-other", "teacher", "teacher-2"));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithTeachingAiTraceTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                null,
                new AgentTraceQueryService(traceStore));

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "get_teaching_ai_trace",
                new McpToolCallRequest(Map.of("taskId", "task-own")));

        assertThat(response.toolName()).isEqualTo("get_teaching_ai_trace");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("traceId")).isEqualTo("trace-own");
        assertThat(result.get("taskId")).isEqualTo("task-own");
        assertThat(result.toString()).contains("JSON_PARSE_SUCCEEDED");
        assertThat(result.toString()).doesNotContain("teacher-2");
    }

    @Test
    void mcpTraceToolDoesNotLeakAnotherSubjectTaskId() throws Exception {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        traceStore.save(trace("trace-other", "task-other", "teacher", "teacher-2"));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithTeachingAiTraceTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                null,
                new AgentTraceQueryService(traceStore));

        assertThatThrownBy(() -> service.callTool(
                        "Bearer teacher_secret_1234567890abcdef",
                        "get_teaching_ai_trace",
                        new McpToolCallRequest(Map.of("taskId", "task-other"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Teaching AI trace not found");
    }

    @Test
    void mcpSecretReadsVisibleAiDiagnosticSummaryWithoutClientSuppliedIdentity() throws Exception {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        traceStore.save(trace("trace-own", "task-own", "teacher", "workbuddy-teacher-subject"));
        traceStore.save(trace("trace-other", "task-other", "teacher", "teacher-2"));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithAiDiagnosticSummaryTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                null,
                new AgentTraceQueryService(traceStore));

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "get_ai_diagnostic_summary",
                new McpToolCallRequest(Map.of(
                        "agentCode", "CoursewareAgent",
                        "status", "COMPLETED",
                        "limit", 20,
                        "subjectId", "teacher-2")));

        assertThat(response.toolName()).isEqualTo("get_ai_diagnostic_summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("subjectId")).isEqualTo("workbuddy-teacher-subject");
        assertThat(result.get("runCount")).isEqualTo(1);
        assertThat(result.get("diagnosticEventCount")).isEqualTo(1);
        assertThat(result.get("jsonParseFailureCount")).isEqualTo(0);
        assertThat(result.toString()).doesNotContain("teacher-2");
    }

    @Test
    void mcpSecretReadsOwnedMultiAgentWritingTraceByWorkflowId() throws Exception {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        traceStore.save(workflowTrace(
                "trace-format",
                "workflow-123:format",
                "workbuddy-teacher-subject",
                "HandoutFormatterAgent",
                7));
        traceStore.save(workflowTrace(
                "trace-draft",
                "workflow-123:draft",
                "workbuddy-teacher-subject",
                "CoursewareAgent",
                11));
        traceStore.save(workflowTrace(
                "trace-other",
                "workflow-123:review",
                "teacher-2",
                "QualityCheckAgent",
                100));
        traceStore.save(workflowTrace(
                "trace-review",
                "workflow-123:review",
                "workbuddy-teacher-subject",
                "QualityCheckAgent",
                5));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithMultiAgentWritingTraceTool(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                null,
                new AgentTraceQueryService(traceStore));

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "get_multi_agent_writing_trace",
                new McpToolCallRequest(Map.of("workflowId", "workflow-123")));

        assertThat(response.toolName()).isEqualTo("get_multi_agent_writing_trace");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("workflowId")).isEqualTo("workflow-123");
        assertThat(result.get("stageCount")).isEqualTo(3);
        assertThat(result.get("totalUsage").toString()).contains("totalTokens=23");
        assertThat(result.toString()).contains("workflow-123:draft", "workflow-123:review", "workflow-123:format");
        assertThat(result.toString()).doesNotContain("teacher-2");
    }

    @Test
    void teacherMcpSecretPlansAgentRunWithoutTrustingClientSuppliedIdentity() throws Exception {
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithPlanAgentTool("teacher", "workbuddy-teacher-subject"),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                null,
                null,
                new AgentRunPlanService(providerCatalog()));

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "plan_agent_run",
                new McpToolCallRequest(Map.of(
                        "agent", "CoursewareAgent",
                        "task", "Create a high-school math handout about space vector angles.",
                        "requestedToolScopes", List.of(
                                "courseware_generate",
                                "textbook_search",
                                "formula_reasoning",
                                "student_progress_write"),
                        "disabledToolScopes", List.of("private_search"),
                        "hasFormula", true,
                        "costBudget", "medium",
                        "latency", "normal",
                        "subjectId", "teacher-2")));

        assertThat(response.toolName()).isEqualTo("plan_agent_run");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("subjectId")).isEqualTo("workbuddy-teacher-subject");
        assertThat(result.get("agentCode")).isEqualTo("CoursewareAgent");
        assertThat(result.get("modelCode")).isEqualTo("qwen3.6-flash");
        assertThat(result.get("capabilityRequired")).isEqualTo(true);
        assertThat(result.get("allowedToolScopes").toString()).contains("tool:courseware:generate", "tool:search:textbook");
        assertThat(result.get("deniedToolScopes").toString()).contains("student_progress_write");
        assertThat(result.get("allowedDataScopes").toString()).contains("PUBLIC_TEXTBOOK");
        assertThat(result.toString()).doesNotContain("teacher-2");
        assertThat(result.get("reactToolPlan")).isInstanceOf(McpReactToolPlan.class);
        McpReactToolPlan reactToolPlan = (McpReactToolPlan) result.get("reactToolPlan");
        assertThat(reactToolPlan.style()).isEqualTo("ReAct");
        assertThat(reactToolPlan.parallelizable()).isTrue();
        assertThat(reactToolPlan.groups()).extracting(McpReactToolPlan.Group::groupId)
                .containsExactly("evidence_parallel", "reasoning_sequential");
        assertThat(reactToolPlan.groups().getFirst().actions())
                .extracting(McpReactToolPlan.Action::toolName)
                .containsExactly("search_textbook_evidence");
        assertThat(reactToolPlan.answerPolicy()).contains("capability-protected");
    }

    @Test
    void studentMcpSecretCannotPlanTeacherOnlyAgentEvenIfToolIsGranted() throws Exception {
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithPlanAgentTool("student", "student-mcp-client"),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                null,
                null,
                new AgentRunPlanService(providerCatalog()));

        assertThatThrownBy(() -> service.callTool(
                        "Bearer teacher_secret_1234567890abcdef",
                        "plan_agent_run",
                        new McpToolCallRequest(Map.of("agentCode", "CoursewareAgent"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent subject not allowed");
    }

    @Test
    void teacherMcpSecretDiscoversFeishuResourcesThroughBackendSubject() {
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithFeishuTool("discover_feishu_resources"),
                null,
                null,
                null,
                null,
                null,
                new TeacherFeishuDiscoveryService(query -> new TeacherFeishuDiscoveryResponse(
                        "feishu-query-1",
                        query.mode(),
                        query.rootUrl(),
                        query.keyword(),
                        query.maxDepth(),
                        1,
                        List.of(new TeacherFeishuDiscoveryResponse.Candidate(
                                "docx",
                                "doc-token",
                                "Space vector note",
                                "Vector/Space vector note",
                                "https://my.feishu.cn/docx/doc-token",
                                1,
                                true)),
                        "ok",
                        "Found 1 Feishu candidates")),
                null,
                null,
                null);

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "discover_feishu_resources",
                new McpToolCallRequest(Map.of(
                        "mode", "search",
                        "keyword", "space vector",
                        "rootUrl", "https://my.feishu.cn/drive/folder/root-token",
                        "maxDepth", 3)));

        assertThat(response.toolName()).isEqualTo("discover_feishu_resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("queryId")).isEqualTo("feishu-query-1");
        assertThat(result.get("candidateCount")).isEqualTo(1);
        assertThat(result.toString()).contains("doc-token", "space vector");
        assertThat(response.subjectId()).isEqualTo("workbuddy-teacher-subject");
    }

    @Test
    void teacherMcpSecretDownloadsFeishuResourceThroughSyncPipeline() throws Exception {
        Path downloaded = tempDir.resolve("downloaded-feishu");
        Files.createDirectories(downloaded);
        Files.writeString(downloaded.resolve("vector.md"), """
                # Space vector

                Dot product method for vector angle.
                """);
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/root-token",
                tempDir.resolve("download_feishu_url.py"),
                tempDir.resolve("APPKEY.md"),
                tempDir.resolve("staging"),
                1);
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncExecutionService executionService = new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                new FixedFeishuDownloadClient(downloaded),
                properties,
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithFeishuTool("download_feishu_resource"),
                null,
                null,
                null,
                null,
                null,
                null,
                resourceService,
                jobService,
                executionService);

        var response = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "download_feishu_resource",
                new McpToolCallRequest(Map.of(
                        "url", "https://my.feishu.cn/drive/folder/root-token",
                        "title", "MCP Feishu vector",
                        "exportFormat", "md")));

        assertThat(response.toolName()).isEqualTo("download_feishu_resource");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertThat(result.get("status")).isEqualTo("completed");
        assertThat(result.get("phase")).isEqualTo("download_completed");
        assertThat(result.get("syncStatus")).isEqualTo("synced");
        assertThat(result.get("parseStatus")).isEqualTo("parsed");
        assertThat(result.get("stagingPath").toString()).contains("downloaded-feishu");
        assertThat(blockStore.listByDocument("default", result.get("documentId").toString()))
                .hasSize(1)
                .first()
                .satisfies(block -> assertThat(block.normalizedText()).contains("Dot product method"));
    }

    @Test
    void teacherMcpSecretStartsAndReadsMultiAgentWritingWorkflow() throws Exception {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded", "teacher draft"),
                new AiChatResult(
                        "dashscope",
                        "qwen3.6-flash",
                        9,
                        5,
                        14,
                        "review recorded",
                        "{\"review\":\"quality review\",\"status\":\"ok\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded", "formatted handout")));
        MultiAgentWritingService writingService = multiAgentWritingService(traceStore, workflowStore, gateway);
        McpToolExecutionService service = McpToolExecutionServiceFixture.service(
                registryWithMultiAgentWritingTools(),
                null,
                null,
                null,
                new AgentTraceQueryService(traceStore),
                null,
                null,
                null,
                null,
                null,
                writingService,
                new MultiAgentWritingArtifactExportService(
                        writingService,
                        Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                        Duration.ofMinutes(10)));

        var started = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "start_multi_agent_writing",
                new McpToolCallRequest(Map.of(
                        "writingGoal", "teacher handout",
                        "questionText", "space vector angle",
                        "evidenceRefs", List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                        "preferredProviderName", "dashscope",
                        "preferredModelCode", "qwen3.6-flash",
                        "subjectId", "teacher-2")));

        assertThat(started.toolName()).isEqualTo("start_multi_agent_writing");
        @SuppressWarnings("unchecked")
        Map<String, Object> startResult = (Map<String, Object>) started.result();
        assertThat(startResult.get("subjectId")).isEqualTo("workbuddy-teacher-subject");
        assertThat(startResult.get("status")).isEqualTo("RUNNING");
        assertThat(startResult.get("stageCount")).isEqualTo(0);
        assertThat(startResult.toString()).doesNotContain("teacher-2");
        assertThat(gateway.requests()).extracting(AiChatRequest::agentCode)
                .containsExactly("CoursewareAgent", "QualityCheckAgent", "HandoutFormatterAgent");

        var status = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "get_multi_agent_writing_status",
                new McpToolCallRequest(Map.of("workflowId", startResult.get("workflowId"))));

        assertThat(status.toolName()).isEqualTo("get_multi_agent_writing_status");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusResult = (Map<String, Object>) status.result();
        assertThat(statusResult.get("workflowId")).isEqualTo(startResult.get("workflowId"));
        assertThat(statusResult.get("status")).isEqualTo("COMPLETED");
        assertThat(statusResult.get("totalUsage").toString()).contains("totalTokens=44");

        var artifact = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "get_multi_agent_writing_artifact",
                new McpToolCallRequest(Map.of("workflowId", startResult.get("workflowId"))));

        assertThat(artifact.toolName()).isEqualTo("get_multi_agent_writing_artifact");
        @SuppressWarnings("unchecked")
        Map<String, Object> artifactResult = (Map<String, Object>) artifact.result();
        assertThat(artifactResult.get("workflowId")).isEqualTo(startResult.get("workflowId"));
        assertThat(artifactResult.get("mergedMarkdown").toString())
                .contains("teacher draft", "quality review", "formatted handout");
        assertThat(artifactResult.toString()).doesNotContain("teacher-2");

        var export = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "export_multi_agent_writing_artifact",
                new McpToolCallRequest(Map.of("workflowId", startResult.get("workflowId"), "format", "markdown")));

        assertThat(export.toolName()).isEqualTo("export_multi_agent_writing_artifact");
        @SuppressWarnings("unchecked")
        Map<String, Object> exportResult = (Map<String, Object>) export.result();
        String exportedMarkdown = new String(
                Base64.getDecoder().decode(exportResult.get("base64Content").toString()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(exportResult.get("format")).isEqualTo("markdown");
        assertThat(exportResult.get("fileName").toString()).endsWith(".md");
        assertThat(exportResult.get("sha256").toString()).hasSize(64);
        assertThat(exportResult.get("expiresAt")).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
        assertThat(exportedMarkdown).contains("teacher draft", "formatted handout");

        var latexExport = service.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "export_multi_agent_writing_artifact",
                new McpToolCallRequest(Map.of("workflowId", startResult.get("workflowId"), "format", "latex")));

        assertThat(latexExport.toolName()).isEqualTo("export_multi_agent_writing_artifact");
        @SuppressWarnings("unchecked")
        Map<String, Object> latexExportResult = (Map<String, Object>) latexExport.result();
        String exportedLatex = new String(
                Base64.getDecoder().decode(latexExportResult.get("base64Content").toString()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(latexExportResult.get("format")).isEqualTo("latex");
        assertThat(latexExportResult.get("fileName").toString()).endsWith(".tex");
        assertThat(latexExportResult.get("mimeType").toString()).contains("application/x-tex");
        assertThat(latexExportResult.get("sha256").toString()).hasSize(64);
        assertThat(exportedLatex)
                .contains("\\documentclass[UTF8]{ctexart}")
                .contains("teacher draft")
                .contains("formatted handout")
                .contains("\\end{document}");
    }
    /**
     * Creates a registry where one WorkBuddy teacher key can call only textbook evidence search.
     */
    private static McpClientRegistryProperties registryWithTeacherTool() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy teacher key can call teacher resource evidence search.
     */
    private static McpClientRegistryProperties registryWithTeacherResourceTool() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("search_teacher_resource_evidence"),
                List.of("teacher-resource:read", "TEACHER_PRIVATE", "MATH_VIP"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy teacher key can read owned teaching AI trace diagnostics.
     */
    private static McpClientRegistryProperties registryWithTeachingAiTraceTool() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("get_teaching_ai_trace"),
                List.of("agent-trace:read"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy teacher key can read aggregate AI diagnostics.
     */
    private static McpClientRegistryProperties registryWithAiDiagnosticSummaryTool() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("get_ai_diagnostic_summary"),
                List.of("agent-trace:read"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy teacher key can read owned writing workflow traces.
     */
    private static McpClientRegistryProperties registryWithMultiAgentWritingTraceTool() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("get_multi_agent_writing_trace"),
                List.of("agent-trace:read"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy key can ask for non-executing agent plans.
     */
    private static McpClientRegistryProperties registryWithPlanAgentTool(String profile, String subjectId) {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-" + profile,
                profile,
                "default",
                subjectId,
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("plan_agent_run"),
                List.of("agent:plan"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy teacher key can call one Feishu MCP tool.
     */
    private static McpClientRegistryProperties registryWithFeishuTool(String toolName) {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of(toolName),
                List.of("teacher-resource:read", "teacher-resource:sync-execute"))));
        return properties;
    }

    /**
     * Creates a registry where one WorkBuddy teacher key can execute and recover writing workflows.
     */
    private static McpClientRegistryProperties registryWithMultiAgentWritingTools() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of(
                        "start_multi_agent_writing",
                        "get_multi_agent_writing_status",
                        "get_multi_agent_writing_artifact",
                        "export_multi_agent_writing_artifact",
                        "get_multi_agent_writing_trace"),
                List.of("agent-writing:execute", "agent-writing:read", "agent-writing:export", "agent-trace:read"))));
        return properties;
    }

    /**
     * Builds enabled AI provider settings for planner-only MCP tests.
     */
    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    /**
     * Builds a real planner/executor-backed writing service with a controlled unit-test gateway.
     */
    private static MultiAgentWritingService multiAgentWritingService(
            InMemoryAgentTraceStore traceStore,
            InMemoryMultiAgentWritingWorkflowStore workflowStore,
            AiChatGateway gateway) {
        AiProviderCatalog catalog = providerCatalog();
        return new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        traceStore,
                        new InMemoryAgentConcurrencyGuard(),
                        gateway,
                        catalog,
                        Clock.systemUTC()),
                workflowStore,
                new org.springframework.core.task.SyncTaskExecutor());
    }

    /**
     * Builds a safe CoursewareAgent trace linked to a teaching task id.
     */
    private static AgentTraceRecord trace(String traceId, String taskId, String subjectType, String subjectId) {
        return new AgentTraceRecord(
                traceId,
                taskId,
                Instant.parse("2026-06-29T00:00:00Z"),
                "default",
                subjectType,
                subjectId,
                "CoursewareAgent",
                "openai",
                "gpt-5.4",
                "COMPLETED",
                0.0,
                List.of("tool:courseware:generate"),
                List.of("data:public_textbook"),
                List.of("PUBLIC_TEXTBOOK:Book:chunk-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("ai_draft", 12)),
                new AgentRunExecuteResponse.TokenUsage(10, 8, 18),
                "Teaching AI draft structured; retry=0/1; recovered=false; events=2",
                List.of(new AgentTraceRecord.DiagnosticEvent(
                        "JSON_PARSE_SUCCEEDED",
                        "openai",
                        "gpt-5.4",
                        0,
                        false,
                        "Structured teaching draft parsed.")));
    }

    /**
     * Builds a safe trace linked to one multi-agent writing stage.
     */
    private static AgentTraceRecord workflowTrace(
            String traceId,
            String planId,
            String subjectId,
            String agentCode,
            int totalTokens) {
        return new AgentTraceRecord(
                traceId,
                planId,
                Instant.parse("2026-06-29T00:00:00Z"),
                "default",
                "teacher",
                subjectId,
                agentCode,
                "dashscope",
                "qwen3.6-flash",
                "COMPLETED",
                0.0,
                List.of("tool:courseware:generate"),
                List.of("PUBLIC_TEXTBOOK"),
                List.of("PUBLIC_TEXTBOOK:Book:chunk-1"),
                List.of(new AgentRunExecuteResponse.StageTiming("model_call", 12)),
                new AgentRunExecuteResponse.TokenUsage(totalTokens - 2, 2, totalTokens),
                "safe workflow trace",
                List.of(new AgentTraceRecord.DiagnosticEvent(
                        "MODEL_CALL_SUCCEEDED",
                        "dashscope",
                        "qwen3.6-flash",
                        0,
                        false,
                        "Model call completed.")));
    }

    /**
     * Builds a teacher resource document response for MCP tool tests.
     */
    private static TeacherResourceDocumentResponse document(
            String documentId,
            String ownerSubjectId,
            String permissionScope,
            String title) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "default",
                ownerSubjectId,
                "feishu",
                title,
                "https://my.feishu.cn/docx/" + documentId,
                "C:/math/" + documentId,
                permissionScope,
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                "md",
                List.of());
    }

    /**
     * Builds a parsed teacher resource block for MCP tool tests.
     */
    private static TeacherDocumentBlockResponse block(String blockId, String documentId, String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockId,
                "text",
                1,
                "Space vector",
                "Normal vector",
                null,
                null,
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }

    private static TeacherDocumentBlockResponse blockWithImageRef(
            String blockId,
            String documentId,
            String text,
            String assetId) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockId,
                "text",
                1,
                "Space vector",
                "Normal vector",
                null,
                null,
                text,
                text.toLowerCase(),
                "[\"" + assetId + "\"]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }

    /**
     * Builds a minimal real processed_books corpus for MCP search.
     */
    private Path textbookCorpus() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_vector");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_vector","book_name":"Vector Textbook","volume":"selective","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"vector_p001","doc_id":"book_vector","book_name":"Vector Textbook","volume":"selective","chapter_path":["Space Vector"],"page_no":1,"printed_page_no":"1","chunk_type":"page_summary","section_title":"Space vector angle","text":"space vector angle dot product geometry","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p001.png"}
                """);
        return root;
    }

    /**
     * Escapes Windows paths for JSON string literals.
     */
    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    /**
     * Test Feishu downloader that returns a real local folder for parser coverage.
     */
    private static final class FixedFeishuDownloadClient implements TeacherFeishuDownloadClient {

        private final Path savedPath;

        /**
         * Creates a fixed-path downloader.
         */
        private FixedFeishuDownloadClient(Path savedPath) {
            this.savedPath = savedPath;
        }

        /**
         * Returns the prepared folder while still letting the sync service parse real files.
         */
        @Override
        public FeishuDownloadResult download(
                String url,
                Path stagingRoot,
                int maxFiles,
                String fileExtension,
                FeishuDownloadCheckpoint checkpoint) {
            return new FeishuDownloadResult(
                    savedPath,
                    1,
                    0,
                    0,
                    "Downloaded 1 Feishu files; skipped 0",
                    FeishuDownloadCheckpoint.empty(),
                    "[]",
                    "[]");
        }
    }

    /**
     * Captures model requests while returning configured deterministic unit-test outcomes.
     */
    private static final class CapturingGateway implements AiChatGateway {
        private final List<AiChatResult> outcomes;
        private final List<AiChatRequest> requests = new ArrayList<>();
        private int index;

        /**
         * Creates the gateway with one outcome per expected model call.
         */
        private CapturingGateway(List<AiChatResult> outcomes) {
            this.outcomes = outcomes;
        }

        /**
         * Records the real executor request and returns the next configured unit-test result.
         */
        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            return outcomes.get(index++);
        }

        /**
         * Returns captured requests for assertion.
         */
        private List<AiChatRequest> requests() {
            return requests;
        }
    }
}
