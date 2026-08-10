package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.controller.A2aAgentCardController;
import com.doob.mathagent.protocol.controller.McpDiscoveryController;
import com.doob.mathagent.protocol.controller.McpToolExecutionController;
import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtocolDiscoveryControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesMcpToolDiscoveryEndpointThroughService() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();
        McpDiscoveryController controller = new McpDiscoveryController(service);

        var tools = controller.tools();

        assertThat(tools).extracting("name")
                .contains(
                        "search_multi_source_evidence",
                        "search_textbook_evidence",
                        "search_teacher_resource_evidence",
                        "list_teacher_resources",
                        "read_teacher_resource_blocks",
                        "search_question_bank_items",
                        "get_teaching_ai_trace",
                        "get_ai_diagnostic_summary",
                        "plan_agent_run");
        assertThat(tools).filteredOn("executionEndpointEnabled", true)
                .extracting("name")
                .containsExactly(
                        "search_multi_source_evidence",
                        "search_textbook_evidence",
                        "search_teacher_resource_evidence",
                        "list_teacher_resources",
                        "read_teacher_resource_blocks",
                        "search_question_bank_items",
                        "get_teaching_ai_trace",
                        "get_ai_diagnostic_summary",
                        "get_multi_agent_writing_trace",
                        "plan_agent_run",
                        "start_multi_agent_writing",
                        "get_multi_agent_writing_status",
                        "get_multi_agent_writing_artifact",
                        "export_multi_agent_writing_artifact",
                        "resume_multi_agent_writing",
                        "discover_feishu_resources",
                        "download_feishu_resource");
    }

    @Test
    void exposesA2aAgentCardEndpointThroughService() {
        ProtocolDiscoveryService service = new ProtocolDiscoveryService();
        A2aAgentCardController controller = new A2aAgentCardController(service);

        var card = controller.agentCard();

        assertThat(card.name()).isEqualTo("Math Agent RAG");
        assertThat(card.skills()).extracting("id")
                .contains("teacher_student_handout_generation");
        assertThat(card.url()).isEqualTo("/api/a2a");
    }

    @Test
    void exposesMcpToolExecutionEndpointThroughService() throws Exception {
        McpToolExecutionController controller = new McpToolExecutionController(McpToolExecutionServiceFixture.service(
                registryWithTextbookSearch(),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus())));

        var response = controller.callTool(
                "Bearer teacher_secret_1234567890abcdef",
                "search_textbook_evidence",
                new McpToolCallRequest(Map.of("query", "space vector", "limit", 2)));

        assertThat(response.toolName()).isEqualTo("search_textbook_evidence");
        assertThat(response.clientId()).isEqualTo("workbuddy-teacher");
        assertThat(response.subjectType()).isEqualTo("teacher");
    }

    /**
     * Creates an MCP registry that allows only textbook evidence search.
     */
    private static McpClientRegistryProperties registryWithTextbookSearch() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "school-a",
                "teacher-mcp-client",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        return properties;
    }

    /**
     * Builds a small real processed textbook corpus for controller-level MCP execution.
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
     * Escapes Windows paths for JSON literals.
     */
    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
