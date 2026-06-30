package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
