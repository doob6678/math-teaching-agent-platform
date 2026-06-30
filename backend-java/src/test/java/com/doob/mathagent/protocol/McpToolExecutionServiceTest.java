package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithTeacherTool(),
                new TextbookRetrievalService(
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
    }

    @Test
    void rejectsWrongSecretAndToolsNotGrantedToClient() throws Exception {
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithTeacherTool(),
                new TextbookRetrievalService(
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
        resourceStore.save(document("doc-own", "workbuddy-teacher-subject", "TEACHER_PRIVATE", "Own Feishu vector notes"));
        resourceStore.save(document("doc-other", "teacher-2", "TEACHER_PRIVATE", "Other private vector notes"));
        blockStore.replaceActiveBlocks("default", "doc-own", List.of(block(
                "b-own",
                "doc-own",
                "Feishu teacher method explains space vector angle with normal vectors.")));
        blockStore.replaceActiveBlocks("default", "doc-other", List.of(block(
                "b-other",
                "doc-other",
                "Another teacher private Feishu method must not leak.")));
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithTeacherResourceTool(),
                new TextbookRetrievalService(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                new TeacherResourceBlockSearchService(resourceStore, blockStore));

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
                List.of("MATH_VIP"))));
        McpToolExecutionService service = new McpToolExecutionService(
                properties,
                new TextbookRetrievalService(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()),
                new TeacherResourceBlockSearchService(
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
    void mcpSecretReadsOnlyOwnedTeachingAiTraceByTaskId() throws Exception {
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        traceStore.save(trace("trace-own", "task-own", "teacher", "workbuddy-teacher-subject"));
        traceStore.save(trace("trace-other", "task-other", "teacher", "teacher-2"));
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithTeachingAiTraceTool(),
                new TextbookRetrievalService(
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
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithTeachingAiTraceTool(),
                new TextbookRetrievalService(
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
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithAiDiagnosticSummaryTool(),
                new TextbookRetrievalService(
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
        McpToolExecutionService service = new McpToolExecutionService(
                registryWithMultiAgentWritingTraceTool(),
                new TextbookRetrievalService(
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
                List.of("TEACHER_PRIVATE", "MATH_VIP"))));
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
}
