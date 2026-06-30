package com.doob.mathagent.protocol.service;

import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentTraceDiagnosticSummaryResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.vo.McpToolCallResponse;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Executes the small set of MCP tools that are safe to expose to external local clients.
 */
@Service
public class McpToolExecutionService {

    private static final String TEXTBOOK_EVIDENCE_TOOL = "search_textbook_evidence";
    private static final String TEACHER_RESOURCE_EVIDENCE_TOOL = "search_teacher_resource_evidence";
    private static final String TEACHING_AI_TRACE_TOOL = "get_teaching_ai_trace";
    private static final String AI_DIAGNOSTIC_SUMMARY_TOOL = "get_ai_diagnostic_summary";
    private static final String MULTI_AGENT_WRITING_TRACE_TOOL = "get_multi_agent_writing_trace";

    private final McpClientRegistryProperties registryProperties;
    private final TextbookRetrievalService textbookRetrievalService;
    private final TextbookResourceProperties textbookResourceProperties;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    private final AgentTraceQueryService agentTraceQueryService;

    /**
     * Creates an MCP execution service.
     *
     * @param registryProperties registered MCP client secrets and allowed tool scopes
     * @param textbookRetrievalService real textbook retrieval service
     * @param textbookResourceProperties processed textbook resource configuration
     */
    @Autowired
    public McpToolExecutionService(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService) {
        this.registryProperties = registryProperties == null ? new McpClientRegistryProperties() : registryProperties;
        this.textbookRetrievalService = textbookRetrievalService;
        this.textbookResourceProperties = textbookResourceProperties;
        this.teacherResourceBlockSearchService = teacherResourceBlockSearchService;
        this.agentTraceQueryService = agentTraceQueryService;
    }

    /**
     * Backward-compatible constructor for tests and production wiring that do not expose trace reads.
     */
    public McpToolExecutionService(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService) {
        this(registryProperties, textbookRetrievalService, textbookResourceProperties, teacherResourceBlockSearchService, null);
    }

    /**
     * Backward-compatible constructor for tests that only exercise textbook evidence search.
     */
    public McpToolExecutionService(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties) {
        this(registryProperties, textbookRetrievalService, textbookResourceProperties, null, null);
    }

    /**
     * Executes one allowed MCP tool using identity resolved from the registered Bearer secret.
     *
     * @param authorizationHeader HTTP Authorization header
     * @param toolName requested MCP tool name
     * @param request tool call request body
     * @return structured tool execution response
     */
    public McpToolCallResponse callTool(
            String authorizationHeader,
            String toolName,
            McpToolCallRequest request) {
        McpClientRegistryProperties.Client client = resolveClient(authorizationHeader);
        String normalizedToolName = normalizeToolName(toolName);
        requireToolAllowed(client, normalizedToolName);
        Object result = switch (normalizedToolName) {
            case TEXTBOOK_EVIDENCE_TOOL -> searchTextbookEvidence(client, request);
            case TEACHER_RESOURCE_EVIDENCE_TOOL -> searchTeacherResourceEvidence(client, request);
            case TEACHING_AI_TRACE_TOOL -> getTeachingAiTrace(client, request);
            case AI_DIAGNOSTIC_SUMMARY_TOOL -> getAiDiagnosticSummary(client, request);
            case MULTI_AGENT_WRITING_TRACE_TOOL -> getMultiAgentWritingTrace(client, request);
            default -> throw new IllegalArgumentException("MCP tool is not implemented: " + normalizedToolName);
        };
        return new McpToolCallResponse(
                normalizedToolName,
                client.clientId(),
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                result);
    }

    /**
     * Resolves a registered MCP client from an Authorization Bearer header.
     */
    private McpClientRegistryProperties.Client resolveClient(String authorizationHeader) {
        String secret = bearerSecret(authorizationHeader);
        return registryProperties.findEnabledClientBySecret(secret)
                .orElseThrow(() -> new IllegalArgumentException("MCP secret is not registered or disabled"));
    }

    /**
     * Executes real textbook retrieval and returns a compact JSON-friendly result.
     */
    private Object searchTextbookEvidence(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String query = stringArgument(arguments, "query");
        int limit = intArgument(arguments, "limit", 10);
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required for search_textbook_evidence");
        }
        TextbookSearchResponse response = textbookRetrievalService.search(
                textbookResourceProperties.processedBooksRoot(),
                new TextbookSearchRequest(query, limit),
                new RetrievalRequestContext(
                        client.tenantId(),
                        normalizedProfile(client.profile()),
                        client.subjectId(),
                        null,
                        "mcp:" + client.clientId(),
                        "mcp-client",
                        "/api/mcp/tools/search_textbook_evidence/call"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryId", response.queryId());
        result.put("query", response.query());
        result.put("limit", response.limit());
        result.put("retrievalStrategy", response.retrievalStrategy());
        result.put("total", response.total());
        result.put("hits", response.hits());
        return result;
    }

    /**
     * Returns safe aggregate AI diagnostics visible to this MCP subject.
     */
    private Object getAiDiagnosticSummary(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        if (agentTraceQueryService == null) {
            throw new IllegalStateException("Agent trace query service is not configured");
        }
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String agentCode = stringArgument(arguments, "agentCode");
        String status = stringArgument(arguments, "status");
        int limit = intArgument(arguments, "limit", 100);
        AgentTraceDiagnosticSummaryResponse summary = agentTraceQueryService.diagnosticSummary(
                new AgentTraceQueryRequest(
                        agentCode.isBlank() ? null : agentCode,
                        status.isBlank() ? null : status,
                        Math.max(1, Math.min(limit, 200))),
                new RequestSubject(
                        client.tenantId(),
                        normalizedProfile(client.profile()),
                        client.subjectId(),
                        "mcp:" + client.clientId()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", summary.tenantId());
        result.put("subjectType", summary.subjectType());
        result.put("subjectId", summary.subjectId());
        result.put("agentCode", summary.agentCode());
        result.put("status", summary.status());
        result.put("runCount", summary.runCount());
        result.put("diagnosticEventCount", summary.diagnosticEventCount());
        result.put("jsonParseFailureCount", summary.jsonParseFailureCount());
        result.put("retryScheduledCount", summary.retryScheduledCount());
        result.put("retryRecoveredCount", summary.retryRecoveredCount());
        result.put("providerRotationCount", summary.providerRotationCount());
        result.put("modelCallFailureCount", summary.modelCallFailureCount());
        result.put("modelDiagnostics", summary.modelDiagnostics());
        return result;
    }

    /**
     * Executes teacher resource block search through the service that enforces owner and scope visibility.
     */
    private Object searchTeacherResourceEvidence(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        if (teacherResourceBlockSearchService == null) {
            throw new IllegalStateException("Teacher resource search service is not configured");
        }
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String query = stringArgument(arguments, "query");
        int limit = intArgument(arguments, "limit", 10);
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required for search_teacher_resource_evidence");
        }
        TeacherResourceBlockSearchResponse response = teacherResourceBlockSearchService.search(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                query,
                limit,
                "/api/mcp/tools/search_teacher_resource_evidence/call");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryId", response.queryId());
        result.put("query", response.query());
        result.put("limit", response.limit());
        result.put("retrievalMode", response.retrievalMode());
        result.put("hitCount", response.hitCount());
        result.put("hits", response.hits());
        return result;
    }

    /**
     * Returns a safe CoursewareAgent trace linked to a teaching task id visible to this MCP subject.
     */
    private Object getTeachingAiTrace(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        if (agentTraceQueryService == null) {
            throw new IllegalStateException("Agent trace query service is not configured");
        }
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String taskId = stringArgument(arguments, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required for get_teaching_ai_trace");
        }
        AgentTraceResponse trace = agentTraceQueryService.list(
                        new AgentTraceQueryRequest("CoursewareAgent", "COMPLETED", taskId, 1),
                        new RequestSubject(
                                client.tenantId(),
                                normalizedProfile(client.profile()),
                                client.subjectId(),
                                "mcp:" + client.clientId()))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Teaching AI trace not found for taskId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", trace.traceId());
        result.put("taskId", trace.planId());
        result.put("createdAt", trace.createdAt());
        result.put("agentCode", trace.agentCode());
        result.put("providerName", trace.providerName());
        result.put("modelCode", trace.modelCode());
        result.put("status", trace.status());
        result.put("actualUsage", trace.actualUsage());
        result.put("stageTimings", trace.stageTimings());
        result.put("diagnosticEvents", trace.diagnosticEvents());
        result.put("message", trace.message());
        result.put("evidenceRefs", trace.evidenceRefs());
        return result;
    }

    /**
     * Returns safe ordered traces for one visible multi-agent writing workflow.
     */
    private Object getMultiAgentWritingTrace(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        if (agentTraceQueryService == null) {
            throw new IllegalStateException("Agent trace query service is not configured");
        }
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String workflowId = normalizedWorkflowId(stringArgument(arguments, "workflowId"));
        List<AgentTraceResponse> stages = agentTraceQueryService.list(
                        new AgentTraceQueryRequest(null, null, null, workflowId + ":", 20),
                        new RequestSubject(
                                client.tenantId(),
                                normalizedProfile(client.profile()),
                                client.subjectId(),
                                "mcp:" + client.clientId()))
                .stream()
                .sorted(Comparator.comparingInt(McpToolExecutionService::stageOrder)
                        .thenComparing(AgentTraceResponse::createdAt))
                .toList();
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("Multi-agent writing workflow trace not found for workflowId");
        }
        int promptTokens = stages.stream().mapToInt(stage -> stage.actualUsage().promptTokens()).sum();
        int completionTokens = stages.stream().mapToInt(stage -> stage.actualUsage().completionTokens()).sum();
        int totalTokens = stages.stream().mapToInt(stage -> stage.actualUsage().totalTokens()).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflowId);
        result.put("tenantId", client.tenantId());
        result.put("subjectType", normalizedProfile(client.profile()));
        result.put("subjectId", client.subjectId());
        result.put("stageCount", stages.size());
        result.put("totalUsage", new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens));
        result.put("stages", stages);
        return result;
    }

    /**
     * Checks the exact tool allow-list configured for this MCP client.
     */
    private static void requireToolAllowed(McpClientRegistryProperties.Client client, String toolName) {
        if (!client.allowedTools().contains(toolName)) {
            throw new IllegalArgumentException("MCP tool is not allowed for client " + client.clientId() + ": " + toolName);
        }
    }

    /**
     * Extracts the Bearer secret from an HTTP Authorization value.
     */
    static String bearerSecret(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("MCP secret is required");
        }
        String normalized = authorizationHeader.strip();
        if (!normalized.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new IllegalArgumentException("MCP secret must use Bearer authorization");
        }
        String secret = normalized.substring("Bearer ".length()).strip();
        if (secret.length() < 16) {
            throw new IllegalArgumentException("MCP secret is too short");
        }
        return secret;
    }

    /**
     * Normalizes a tool name supplied by an external client.
     */
    private static String normalizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("MCP tool name is required");
        }
        return toolName.strip();
    }

    /**
     * Validates workflow id shape before using it as a trace prefix.
     */
    private static String normalizedWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required for get_multi_agent_writing_trace");
        }
        String normalized = workflowId.strip();
        if (!normalized.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new IllegalArgumentException("workflowId is invalid");
        }
        return normalized;
    }

    /**
     * Sorts known writing stages into the execution order.
     */
    private static int stageOrder(AgentTraceResponse trace) {
        String planId = trace.planId() == null ? "" : trace.planId();
        if (planId.endsWith(":draft")) {
            return 0;
        }
        if (planId.endsWith(":review")) {
            return 1;
        }
        if (planId.endsWith(":format")) {
            return 2;
        }
        return 99;
    }

    /**
     * Reads a string argument from a JSON-like argument map.
     */
    private static String stringArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * Reads a bounded integer argument from a JSON-like argument map.
     */
    private static int intArgument(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    /**
     * Normalizes MCP profile text to backend subject type values.
     */
    private static String normalizedProfile(String profile) {
        return profile == null || profile.isBlank() ? "teacher" : profile.strip().toLowerCase();
    }
}
