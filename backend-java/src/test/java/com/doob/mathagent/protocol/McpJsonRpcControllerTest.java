package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryMultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.ApiAccessLevel;
import com.doob.mathagent.infrastructure.security.ApiAccessPolicy;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineProperties;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineSeedService;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.protocol.controller.McpJsonRpcController;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpJsonRpcService;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.resources.TextbookResourceService;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teacher.TeacherResourceServiceFixture;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class McpJsonRpcControllerTest {

    private static final String AUTHORIZATION = "Bearer teacher_secret_1234567890abcdef";
    private static final String ACCEPT = "application/json, text/event-stream";

    @TempDir
    Path tempDir;

    @Test
    void initializeReturnsCurrentMcpServerMetadata() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("jsonrpc", "2.0");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("protocolVersion", "2025-11-25");
        assertThat(result.get("serverInfo").toString()).contains("math-agent-rag");
    }

    @Test
    void supportedOlderProtocolVersionIsEchoedInResponseHeaderAndInitializeBody() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-06-18",
                """
                        {"jsonrpc":"2.0","id":"older-protocol","method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-06-18");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("protocolVersion", "2025-06-18");

        var badAccept = controller.post(
                AUTHORIZATION,
                "application/json",
                "2025-06-18",
                """
                        {"jsonrpc":"2.0","id":"older-protocol-bad-accept","method":"ping","params":{}}
                        """,
                localRequest());

        assertThat(badAccept.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(badAccept.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-06-18");
    }

    @Test
    void missingHttpProtocolVersionUsesStandardFallbackExceptInitializeNegotiation() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var initializeResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                null,
                """
                        {"jsonrpc":"2.0","id":"init-no-header","method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                        """,
                localRequest());
        var pingResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                null,
                """
                        {"jsonrpc":"2.0","id":"ping-no-header","method":"ping","params":{}}
                        """,
                localRequest());
        var getResponse = controller.get(null, localRequest());

        assertThat(initializeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initializeResponse.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        assertThat(pingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pingResponse.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-03-26");
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(getResponse.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-03-26");
    }

    @Test
    void rejectsConflictingInitializeProtocolVersionSignals() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-06-18",
                """
                        {"jsonrpc":"2.0","id":"init-conflict","method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-06-18");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("error").toString()).contains("must match");
    }

    @Test
    void listsOnlyToolsAllowedByRegisteredBearerSecret() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"tools-1","method":"tools/list","params":{}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).extracting(tool -> tool.get("name"))
                .containsExactly("search_textbook_evidence");
    }

    @Test
    void toolsListDoesNotAdvertiseDescriptorsWithoutExecutionEndpoint() throws Exception {
        McpJsonRpcController controller = controller(registryWithDisabledEndpointTools());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"tools-disabled","method":"tools/list","params":{}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).extracting(tool -> tool.get("name"))
                .containsExactly("search_textbook_evidence");
    }

    @Test
    void listsPlanToolWithStandardAnnotationsAndRichSchema() throws Exception {
        McpJsonRpcController controller = controller(registryWithPlanAgentRun());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"tools-plan","method":"tools/list","params":{}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).hasSize(1);
        Map<String, Object> tool = tools.getFirst();
        assertThat(tool.get("name")).isEqualTo("plan_agent_run");
        assertThat(tool.get("annotations").toString()).contains("readOnlyHint=true", "openWorldHint=false");
        assertThat(tool.get("inputSchema").toString())
                .contains("preferredProviderName", "preferredModelCode", "requestedDataScopes", "requestedToolScopes")
                .contains("items={type=string}");
    }

    @Test
    void listsPromptsAllowedByRegisteredTeacherProfile() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"prompts-1","method":"prompts/list","params":{}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");
        assertThat(prompts).extracting(prompt -> prompt.get("name"))
                .containsExactly("teacher_handout_writer", "student_blank_handout_writer", "solution_reviewer");
    }

    @Test
    void studentProfileCannotReadTeacherOnlyPrompt() throws Exception {
        McpJsonRpcController controller = controller(registryWithStudentTextbookSearch());

        var listResponse = controller.post(
                "Bearer student_secret_1234567890abcdef",
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"student-prompts","method":"prompts/list","params":{}}
                        """,
                localRequest());
        var getResponse = controller.post(
                "Bearer student_secret_1234567890abcdef",
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"student-get","method":"prompts/get","params":{"name":"teacher_handout_writer","arguments":{"topic":"function zero points"}}}
                        """,
                localRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> listBody = (Map<String, Object>) listResponse.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> listResult = (Map<String, Object>) listBody.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prompts = (List<Map<String, Object>>) listResult.get("prompts");
        assertThat(prompts).extracting(prompt -> prompt.get("name"))
                .containsExactly("student_blank_handout_writer", "solution_reviewer");
        @SuppressWarnings("unchecked")
        Map<String, Object> getBody = (Map<String, Object>) getResponse.getBody();
        assertThat(getBody.get("error").toString()).contains("not allowed");
    }

    @Test
    void getsPromptAsStandardMcpMessages() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"prompt-get","method":"prompts/get","params":{"name":"teacher_handout_writer","arguments":{"topic":"function zero points","evidence":"Textbook chapter 3.1"}}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result.get("description").toString()).contains("teacher-version");
        assertThat(result.get("messages").toString())
                .contains("teacher-version high school math handout")
                .contains("function zero points")
                .contains("Textbook chapter 3.1");
    }

    @Test
    void listsAndReadsOnlyApplicationOwnedResources() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var listResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"resources-1","method":"resources/list","params":{}}
                        """,
                localRequest());
        var readResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"resource-read","method":"resources/read","params":{"uri":"math-agent://textbooks/summary"}}
                        """,
                localRequest());
        var graphReadResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"graph-read","method":"resources/read","params":{"uri":"math-agent://knowledge/graph-spine/v0.1"}}
                        """,
                localRequest());
        var unsafeResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"resource-unsafe","method":"resources/read","params":{"uri":"file:///C:/Users/doob/secret.txt"}}
                        """,
                localRequest());
        var templatesResponse = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"resource-templates","method":"resources/templates/list","params":{}}
                        """,
                localRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> listBody = (Map<String, Object>) listResponse.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> listResult = (Map<String, Object>) listBody.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resources = (List<Map<String, Object>>) listResult.get("resources");
        assertThat(resources).extracting(resource -> resource.get("uri"))
                .containsExactly(
                        "math-agent://textbooks/summary",
                        "math-agent://knowledge/graph-spine/v0.1",
                        "math-agent://protocol/capabilities");
        @SuppressWarnings("unchecked")
        Map<String, Object> readBody = (Map<String, Object>) readResponse.getBody();
        assertThat(readBody.get("result").toString()).contains("Vector Textbook", "bookCount", "application/json");
        assertThat(readBody.get("result").toString()).doesNotContain("C:", "book_root", "manifest", "processedBooksRoot");
        @SuppressWarnings("unchecked")
        Map<String, Object> graphReadBody = (Map<String, Object>) graphReadResponse.getBody();
        assertThat(graphReadBody.get("result").toString())
                .contains("knowledge/graph-spine/v0.1", "\u51fd\u6570", "\u5bfc\u6570", "nodeCount", "edgeCount")
                .doesNotContain("teacher-spoofed");
        @SuppressWarnings("unchecked")
        Map<String, Object> unsafeBody = (Map<String, Object>) unsafeResponse.getBody();
        assertThat(unsafeBody.get("error").toString()).contains("-32002", "not found");
        @SuppressWarnings("unchecked")
        Map<String, Object> templatesBody = (Map<String, Object>) templatesResponse.getBody();
        assertThat(templatesBody.get("result").toString()).contains("resourceTemplates=[]");
    }

    @Test
    void capabilityResourceIsFilteredByRegisteredClientProfileAndToolAllowList() throws Exception {
        McpJsonRpcController teacherController = controller(registryWithTextbookSearch());
        McpJsonRpcController studentController = controller(registryWithStudentTextbookSearch());

        var teacherResponse = teacherController.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"teacher-capabilities","method":"resources/read","params":{"uri":"math-agent://protocol/capabilities"}}
                        """,
                localRequest());
        var studentResponse = studentController.post(
                "Bearer student_secret_1234567890abcdef",
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"student-capabilities","method":"resources/read","params":{"uri":"math-agent://protocol/capabilities"}}
                        """,
                localRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> teacherBody = (Map<String, Object>) teacherResponse.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> studentBody = (Map<String, Object>) studentResponse.getBody();
        assertThat(teacherBody.get("result").toString())
                .contains("search_textbook_evidence", "teacher_handout_writer")
                .doesNotContain("search_teacher_resource_evidence");
        assertThat(studentBody.get("result").toString())
                .contains("search_textbook_evidence", "student_blank_handout_writer")
                .doesNotContain("teacher_handout_writer", "search_teacher_resource_evidence");
    }

    @Test
    void callsToolThroughStandardMcpContentEnvelope() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"search_textbook_evidence","arguments":{"query":"space vector angle","limit":2}}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("isError", false);
        assertThat(result.get("content").toString()).contains("space vector angle", "Vector Textbook");
        assertThat(result.get("structuredContent").toString()).contains("space vector angle");
    }

    @Test
    void toolExecutionFailureUsesStandardMcpToolErrorEnvelope() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"call-error","method":"tools/call","params":{"name":"search_textbook_evidence","arguments":{"limit":2}}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).doesNotContainKey("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("isError", true);
        assertThat(result.get("content").toString()).contains("MCP tool execution failed");
        assertThat(result.get("structuredContent").toString())
                .contains("search_textbook_evidence", "query is required", "retryable=false")
                .doesNotContain("teacher_secret_1234567890abcdef");
    }

    @Test
    void toolAuthorizationFailureStaysJsonRpcError() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"call-denied","method":"tools/call","params":{"name":"plan_agent_run","arguments":{}}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("error").toString()).contains("-32602", "not allowed");
        assertThat(body).doesNotContainKey("result");
    }

    @Test
    void standardMcpJsonRpcRunsMultiAgentWritingAndExportsArtifact() throws Exception {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("dashscope", "qwen3.6-flash", 11, 7, 18, "draft recorded", "# Teacher Draft\n- Vector angle: $\\cos\\theta=\\frac{a\\cdot b}{|a||b|}$"),
                new AiChatResult("dashscope", "qwen3.6-flash", 9, 5, 14, "review recorded", "{\"review\":\"## Quality Review\\nKnowledge point: space vector angle\",\"status\":\"ok\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 8, 4, 12, "format recorded", "### Final Handout\nFormatted handout")));
        McpJsonRpcController controller = multiAgentWritingController(gateway);

        Map<String, Object> started = structuredContent(controller, "start-1", "start_multi_agent_writing", Map.of(
                "writingGoal", "teacher handout",
                "questionText", "space vector angle",
                "evidenceRefs", List.of("PUBLIC_TEXTBOOK:space-vector:angle"),
                "preferredProviderName", "dashscope",
                "preferredModelCode", "qwen3.6-flash",
                "subjectId", "teacher-spoofed"));

        assertThat(started.get("status")).isEqualTo("RUNNING");
        assertThat(started.get("stageCount")).isEqualTo(0);
        assertThat(started.get("subjectId")).isEqualTo("workbuddy-teacher-subject");
        assertThat(started.toString()).doesNotContain("teacher-spoofed");
        assertThat(gateway.requests()).extracting(AiChatRequest::agentCode)
                .containsExactly("CoursewareAgent", "QualityCheckAgent", "HandoutFormatterAgent");
        String workflowId = started.get("workflowId").toString();

        Map<String, Object> status = structuredContent(controller, "status-1", "get_multi_agent_writing_status", Map.of(
                "workflowId", workflowId));
        assertThat(status.get("status")).isEqualTo("COMPLETED");
        assertThat(status.get("stageCount")).isEqualTo(3);
        assertThat(status.get("totalUsage").toString()).contains("totalTokens=44");

        Map<String, Object> artifact = structuredContent(controller, "artifact-1", "get_multi_agent_writing_artifact", Map.of(
                "workflowId", workflowId));
        assertThat(artifact.get("mergedMarkdown").toString())
                .contains("Teacher Draft", "Quality Review", "Formatted handout")
                .doesNotContain("{\"review\"");

        Map<String, Object> export = structuredContent(controller, "export-1", "export_multi_agent_writing_artifact", Map.of(
                "workflowId", workflowId,
                "format", "latex"));
        String latex = new String(Base64.getDecoder().decode(export.get("base64Content").toString()), StandardCharsets.UTF_8);
        assertThat(export.get("format")).isEqualTo("latex");
        assertThat(export.get("expiresAt")).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
        assertThat(latex)
                .contains("\\documentclass[UTF8]{ctexart}")
                .contains("\\section*{Teacher Draft}")
                .contains("\\subsection*{Quality Review}")
                .contains("\\subsubsection*{Final Handout}");
    }

    @Test
    void standardMcpJsonRpcDiscoversAndDownloadsFeishuResourceThroughSyncPipeline() throws Exception {
        Path downloaded = tempDir.resolve("downloaded-feishu");
        Files.createDirectories(downloaded);
        Files.writeString(downloaded.resolve("vector.md"), """
                # Space vector

                Dot product method for vector angle.
                """);
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        McpJsonRpcController controller = feishuController(
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
                new FixedFeishuDownloadClient(downloaded),
                blockStore);

        Map<String, Object> discovered = structuredContent(controller, "feishu-discover", "discover_feishu_resources", Map.of(
                "mode", "search",
                "keyword", "space vector",
                "rootUrl", "https://my.feishu.cn/drive/folder/root-token",
                "maxDepth", 3));
        Map<String, Object> downloadedResult = structuredContent(controller, "feishu-download", "download_feishu_resource", Map.of(
                "url", "https://my.feishu.cn/drive/folder/root-token",
                "title", "MCP Feishu vector",
                "exportFormat", "md"));

        assertThat(discovered.get("candidateCount")).isEqualTo(1);
        assertThat(discovered.toString()).contains("doc-token", "space vector");
        assertThat(downloadedResult.get("status")).isEqualTo("completed");
        assertThat(downloadedResult.get("phase")).isEqualTo("download_completed");
        assertThat(downloadedResult.get("syncStatus")).isEqualTo("synced");
        assertThat(downloadedResult.get("parseStatus")).isEqualTo("parsed");
        assertThat(downloadedResult.get("stagingPath").toString()).contains("downloaded-feishu");
        assertThat(blockStore.listByDocument("default", downloadedResult.get("documentId").toString()))
                .hasSize(1)
                .first()
                .satisfies(block -> assertThat(block.normalizedText()).contains("Dot product method"));
    }

    @Test
    void notificationReturnsAcceptedWithoutBody() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        assertThat(response.getBody()).isNull();
    }

    @Test
    void invalidNotificationIsRejectedBeforeSilentAccept() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"1.0","method":"notifications/initialized","params":{}}
                        """,
                localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("error").toString()).contains("-32600");
    }

    @Test
    void rejectsInvalidStreamableHttpHeadersAndUnsupportedMethods() throws Exception {
        McpJsonRpcController controller = controller(registryWithTextbookSearch());

        var badAccept = controller.post(
                AUTHORIZATION,
                "application/json",
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":1,"method":"ping"}
                        """,
                localRequest());
        var badOriginRequest = localRequest();
        badOriginRequest.addHeader("Origin", "https://evil.example.com");
        var badOrigin = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":1,"method":"ping"}
                        """,
                badOriginRequest);

        assertThat(badAccept.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(badAccept.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        assertThat(badOrigin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(badOrigin.getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        assertThat(controller.get("2025-11-25", localRequest()).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(controller.get("2025-11-25", localRequest()).getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
        assertThat(controller.delete("2025-11-25", localRequest()).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(controller.delete("2025-11-25", localRequest()).getHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");

        var badDeleteOriginRequest = localRequest();
        badDeleteOriginRequest.addHeader("Origin", "https://evil.example.com");
        var badDeleteOrigin = controller.delete("2025-11-25", badDeleteOriginRequest);
        assertThat(badDeleteOrigin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void apiPolicyProtectsStandardMcpEndpoint() {
        var rule = ApiAccessPolicy.defaultRules()
                .findRule("/api/mcp")
                .orElseThrow();

        assertThat(rule.level()).isEqualTo(ApiAccessLevel.GUEST);
        assertThat(rule.allowedSubjectTypes()).containsExactlyInAnyOrder("anonymous", "guest", "student", "teacher", "admin");
    }

    /**
     * Creates a standard MCP controller wired to real services.
     */
    private McpJsonRpcController controller(McpClientRegistryProperties registryProperties) throws Exception {
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        new KnowledgeGraphSpineSeedService(knowledgeStore, new KnowledgeGraphSpineProperties())
                .seedFromConfiguredSource();
        McpToolExecutionService executionService = McpToolExecutionServiceFixture.service(
                registryProperties,
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        new NoopRetrievalAuditSink()),
                new TextbookResourceProperties(textbookCorpus()));
        return new McpJsonRpcController(McpJsonRpcServiceFixture.service(
                new ProtocolDiscoveryService(registryProperties),
                executionService,
                registryProperties,
                new TextbookResourceService(new TextbookCatalogReader()),
                new TextbookResourceProperties(textbookCorpus()),
                new KnowledgeGraphSpineService(knowledgeStore)));
    }

    /**
     * Creates a standard MCP controller wired to the real multi-agent writing planner, executor, store, and export service.
     */
    private static McpJsonRpcController multiAgentWritingController(CapturingGateway gateway) {
        McpClientRegistryProperties registryProperties = registryWithMultiAgentWritingTools();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        InMemoryMultiAgentWritingWorkflowStore workflowStore = new InMemoryMultiAgentWritingWorkflowStore();
        AiProviderCatalog catalog = providerCatalog();
        MultiAgentWritingService writingService = new MultiAgentWritingService(
                new AgentRunPlanService(catalog),
                new AgentRunExecutionService(
                        traceStore,
                        new InMemoryAgentConcurrencyGuard(),
                        gateway,
                        catalog,
                        Clock.systemUTC()),
                workflowStore,
                new org.springframework.core.task.SyncTaskExecutor());
        MultiAgentWritingArtifactExportService exportService = new MultiAgentWritingArtifactExportService(
                writingService,
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(10));
        McpToolExecutionService executionService = McpToolExecutionServiceFixture.service(
                registryProperties,
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
                exportService);
        return new McpJsonRpcController(McpJsonRpcServiceFixture.service(
                new ProtocolDiscoveryService(registryProperties),
                executionService,
                registryProperties));
    }

    /**
     * Creates a standard MCP controller wired to the real Feishu discovery and sync pipeline.
     */
    private McpJsonRpcController feishuController(
            TeacherFeishuDiscoveryService discoveryService,
            TeacherFeishuDownloadClient downloadClient,
            InMemoryTeacherDocumentBlockStore blockStore) {
        McpClientRegistryProperties registryProperties = registryWithFeishuTools();
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
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
                downloadClient,
                properties,
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));
        McpToolExecutionService toolExecutionService = McpToolExecutionServiceFixture.service(
                registryProperties,
                null,
                null,
                null,
                null,
                null,
                discoveryService,
                resourceService,
                jobService,
                executionService);
        return new McpJsonRpcController(McpJsonRpcServiceFixture.service(
                new ProtocolDiscoveryService(registryProperties),
                toolExecutionService,
                registryProperties));
    }

    /**
     * Calls one standard MCP tool through JSON-RPC and returns its structuredContent map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> structuredContent(
            McpJsonRpcController controller,
            String id,
            String toolName,
            Map<String, Object> arguments) {
        var response = controller.post(
                AUTHORIZATION,
                ACCEPT,
                "2025-11-25",
                """
                        {"jsonrpc":"2.0","id":"%s","method":"tools/call","params":{"name":"%s","arguments":%s}}
                        """.formatted(id, toolName, json(arguments)),
                localRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).doesNotContainKey("error");
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).containsEntry("isError", false);
        return (Map<String, Object>) result.get("structuredContent");
    }

    /**
     * Serializes test arguments to compact JSON.
     */
    private static String json(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize MCP test arguments", exception);
        }
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
     * Creates an MCP registry that is intentionally over-broad to verify tools/list never advertises disabled endpoints.
     */
    private static McpClientRegistryProperties registryWithDisabledEndpointTools() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "school-a",
                "teacher-mcp-client",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("search_textbook_evidence", "create_teaching_task", "export_handout_pdf", "list_teacher_resources"),
                List.of("PUBLIC_TEXTBOOK", "teaching:write", "teaching:export", "teacher-resource:read"))));
        return properties;
    }

    /**
     * Creates an MCP registry where WorkBuddy can execute and read teacher writing workflows.
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
                        "export_multi_agent_writing_artifact"),
                List.of("agent-writing:execute", "agent-writing:read", "agent-writing:export"))));
        return properties;
    }

    /**
     * Creates an MCP registry where WorkBuddy can discover and download Feishu resources.
     */
    private static McpClientRegistryProperties registryWithFeishuTools() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "default",
                "workbuddy-teacher-subject",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("discover_feishu_resources", "download_feishu_resource"),
                List.of("teacher-resource:read", "teacher-resource:sync-execute"))));
        return properties;
    }

    /**
     * Builds enabled provider settings for deterministic multi-agent writing tests.
     */
    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        return new AiProviderCatalog(properties);
    }

    /**
     * Creates a student MCP registry for prompt visibility tests.
     */
    private static McpClientRegistryProperties registryWithStudentTextbookSearch() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-student",
                "student",
                "school-a",
                "student-mcp-client",
                McpClientRegistryProperties.secretHash("student_secret_1234567890abcdef"),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        return properties;
    }

    /**
     * Creates an MCP registry that allows only agent planning.
     */
    private static McpClientRegistryProperties registryWithPlanAgentRun() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "school-a",
                "teacher-mcp-client",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("plan_agent_run"),
                List.of("agent:plan"))));
        return properties;
    }

    /**
     * Builds a local request accepted by the Origin guard.
     */
    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("127.0.0.1");
        return request;
    }

    /**
     * Builds a small real processed textbook corpus for standard MCP tool execution.
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

    /**
     * Captures real executor requests while returning configured model outcomes for deterministic MCP tests.
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
         * Records the backend model request and returns the next configured response.
         */
        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            return outcomes.get(index++);
        }

        /**
         * Returns captured backend model requests.
         */
        private List<AiChatRequest> requests() {
            return requests;
        }
    }

    /**
     * Returns a prepared local Feishu download folder while the sync service still parses real files.
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
         * Returns the prepared folder and a real download summary for downstream parsing.
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
}
