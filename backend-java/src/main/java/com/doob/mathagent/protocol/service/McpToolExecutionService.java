package com.doob.mathagent.protocol.service;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.dto.AgentTraceQueryRequest;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.service.AgentTraceQueryService;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifact;
import com.doob.mathagent.agent.service.MultiAgentWritingArtifactExportService;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.agent.vo.AgentTraceDiagnosticSummaryResponse;
import com.doob.mathagent.agent.vo.AgentTraceResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.vo.McpReactToolPlan;
import com.doob.mathagent.protocol.vo.McpToolCallResponse;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Executes the small set of MCP tools that are safe to expose to external local clients.
 */
@Service
public class McpToolExecutionService {

    private static final String MULTI_SOURCE_EVIDENCE_TOOL = "search_multi_source_evidence";
    private static final String TEXTBOOK_EVIDENCE_TOOL = "search_textbook_evidence";
    private static final String TEACHER_RESOURCE_EVIDENCE_TOOL = "search_teacher_resource_evidence";
    private static final String TEACHING_AI_TRACE_TOOL = "get_teaching_ai_trace";
    private static final String AI_DIAGNOSTIC_SUMMARY_TOOL = "get_ai_diagnostic_summary";
    private static final String MULTI_AGENT_WRITING_TRACE_TOOL = "get_multi_agent_writing_trace";
    private static final String PLAN_AGENT_RUN_TOOL = "plan_agent_run";
    private static final String DISCOVER_FEISHU_RESOURCES_TOOL = "discover_feishu_resources";
    private static final String DOWNLOAD_FEISHU_RESOURCE_TOOL = "download_feishu_resource";
    private static final String START_MULTI_AGENT_WRITING_TOOL = "start_multi_agent_writing";
    private static final String GET_MULTI_AGENT_WRITING_STATUS_TOOL = "get_multi_agent_writing_status";
    private static final String GET_MULTI_AGENT_WRITING_ARTIFACT_TOOL = "get_multi_agent_writing_artifact";
    private static final String EXPORT_MULTI_AGENT_WRITING_ARTIFACT_TOOL = "export_multi_agent_writing_artifact";
    private static final String RESUME_MULTI_AGENT_WRITING_TOOL = "resume_multi_agent_writing";

    private final McpClientResolver clientResolver;
    private final TextbookRetrievalService textbookRetrievalService;
    private final TextbookResourceProperties textbookResourceProperties;
    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    private final AgentTraceQueryService agentTraceQueryService;
    private final AgentRunPlanService agentRunPlanService;
    private final TeacherFeishuDiscoveryService teacherFeishuDiscoveryService;
    private final TeacherResourceService teacherResourceService;
    private final TeacherSourceSyncJobService teacherSourceSyncJobService;
    private final TeacherSourceSyncExecutionService teacherSourceSyncExecutionService;
    private final MultiAgentWritingService multiAgentWritingService;
    private final MultiAgentWritingArtifactExportService multiAgentWritingArtifactExportService;

    /**
     * Creates an MCP execution service.
     *
     * @param clientResolver registered MCP client secret resolver
     * @param textbookRetrievalService real textbook retrieval service
     * @param textbookResourceProperties processed textbook resource configuration
     */
    @Autowired
    public McpToolExecutionService(
            McpClientResolver clientResolver,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties,
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            AgentTraceQueryService agentTraceQueryService,
            AgentRunPlanService agentRunPlanService,
            TeacherFeishuDiscoveryService teacherFeishuDiscoveryService,
            TeacherResourceService teacherResourceService,
            TeacherSourceSyncJobService teacherSourceSyncJobService,
            TeacherSourceSyncExecutionService teacherSourceSyncExecutionService,
            MultiAgentWritingService multiAgentWritingService,
            MultiAgentWritingArtifactExportService multiAgentWritingArtifactExportService) {
        this.clientResolver = Objects.requireNonNull(clientResolver, "clientResolver is required");
        this.textbookRetrievalService = Objects.requireNonNull(textbookRetrievalService, "textbookRetrievalService is required");
        this.textbookResourceProperties = Objects.requireNonNull(textbookResourceProperties, "textbookResourceProperties is required");
        this.teacherResourceBlockSearchService = Objects.requireNonNull(
                teacherResourceBlockSearchService, "teacherResourceBlockSearchService is required");
        this.agentTraceQueryService = Objects.requireNonNull(agentTraceQueryService, "agentTraceQueryService is required");
        this.agentRunPlanService = Objects.requireNonNull(agentRunPlanService, "agentRunPlanService is required");
        this.teacherFeishuDiscoveryService = Objects.requireNonNull(
                teacherFeishuDiscoveryService, "teacherFeishuDiscoveryService is required");
        this.teacherResourceService = Objects.requireNonNull(teacherResourceService, "teacherResourceService is required");
        this.teacherSourceSyncJobService = Objects.requireNonNull(
                teacherSourceSyncJobService, "teacherSourceSyncJobService is required");
        this.teacherSourceSyncExecutionService = Objects.requireNonNull(
                teacherSourceSyncExecutionService, "teacherSourceSyncExecutionService is required");
        this.multiAgentWritingService = Objects.requireNonNull(
                multiAgentWritingService, "multiAgentWritingService is required");
        this.multiAgentWritingArtifactExportService = Objects.requireNonNull(
                multiAgentWritingArtifactExportService, "multiAgentWritingArtifactExportService is required");
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
        requireToolScope(client, normalizedToolName);
        Object result = switch (normalizedToolName) {
            case MULTI_SOURCE_EVIDENCE_TOOL -> searchMultiSourceEvidence(client, request);
            case TEXTBOOK_EVIDENCE_TOOL -> searchTextbookEvidence(client, request);
            case TEACHER_RESOURCE_EVIDENCE_TOOL -> searchTeacherResourceEvidence(client, request);
            case TEACHING_AI_TRACE_TOOL -> getTeachingAiTrace(client, request);
            case AI_DIAGNOSTIC_SUMMARY_TOOL -> getAiDiagnosticSummary(client, request);
            case MULTI_AGENT_WRITING_TRACE_TOOL -> getMultiAgentWritingTrace(client, request);
            case PLAN_AGENT_RUN_TOOL -> planAgentRun(client, request);
            case DISCOVER_FEISHU_RESOURCES_TOOL -> discoverFeishuResources(client, request);
            case DOWNLOAD_FEISHU_RESOURCE_TOOL -> downloadFeishuResource(client, request);
            case START_MULTI_AGENT_WRITING_TOOL -> startMultiAgentWriting(client, request);
            case GET_MULTI_AGENT_WRITING_STATUS_TOOL -> getMultiAgentWritingStatus(client, request);
            case GET_MULTI_AGENT_WRITING_ARTIFACT_TOOL -> getMultiAgentWritingArtifact(client, request);
            case EXPORT_MULTI_AGENT_WRITING_ARTIFACT_TOOL -> exportMultiAgentWritingArtifact(client, request);
            case RESUME_MULTI_AGENT_WRITING_TOOL -> resumeMultiAgentWriting(client, request);
            default -> throw new IllegalArgumentException("MCP tool has no backend executor: " + normalizedToolName);
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
        return clientResolver.findEnabledClientBySecret(secret)
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
     * Searches public textbooks and visible teacher resources through one backend entrypoint.
     *
     * <p>This tool is the preferred AI-facing search path because it avoids the common failure mode where the model
     * picks only one library first and never reaches the corpus that actually contains the answer. Callers may still
     * constrain the search to specific libraries or teacher-resource source types when they need a narrower scope.</p>
     */
    private Object searchMultiSourceEvidence(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String query = stringArgument(arguments, "query");
        int limit = intArgument(arguments, "limit", 10);
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required for search_multi_source_evidence");
        }
        SourceSelection selection = sourceSelection(arguments);
        List<String> teacherSourceTypes = mergeDistinct(
                selection.teacherSourceTypes(),
                flexibleStringListArgument(arguments, "sourceTypes", "sourceType"));
        List<TextbookSearchHit> textbookHits = selection.includeTextbook()
                ? textbookRetrievalService.search(
                                textbookResourceProperties.processedBooksRoot(),
                                new TextbookSearchRequest(query, limit),
                                new RetrievalRequestContext(
                                        client.tenantId(),
                                        normalizedProfile(client.profile()),
                                        client.subjectId(),
                                        null,
                                        "mcp:" + client.clientId(),
                                        "mcp-client",
                                        "/api/mcp/tools/search_multi_source_evidence/call"))
                        .hits()
                : List.of();
        List<TeacherResourceBlockSearchResponse.Hit> teacherHits = selection.includeTeacherResources()
                ? teacherResourceBlockSearchService.search(
                                client.tenantId(),
                                normalizedProfile(client.profile()),
                                client.subjectId(),
                                query,
                                limit,
                                "/api/mcp/tools/search_multi_source_evidence/call",
                                TeacherResourceSearchFilter.of(
                                        flexibleStringListArgument(arguments, "permissionScopes", "permissionScope"),
                                        flexibleStringListArgument(arguments, "documentIds", "documentId"),
                                        teacherSourceTypes,
                                        flexibleStringListArgument(arguments, "tags", "tag")))
                        .hits()
                : List.of();
        List<Map<String, Object>> mergedHits = mergedEvidenceHits(textbookHits, teacherHits, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("limit", limit);
        result.put("libraries", selection.libraries());
        result.put("sources", Map.of(
                "textbook", Map.of("enabled", selection.includeTextbook(), "hitCount", textbookHits.size()),
                "teacherResource", Map.of(
                        "enabled", selection.includeTeacherResources(),
                        "hitCount", teacherHits.size(),
                        "sourceTypes", teacherSourceTypes)));
        result.put("textbookHits", textbookHits);
        result.put("teacherResourceHits", teacherHits);
        result.put("mergedHits", mergedHits);
        result.put(
                "evidenceRefs",
                mergedHits.stream()
                        .map(hit -> String.valueOf(hit.get("evidenceRef")))
                        .filter(value -> !value.isBlank())
                        .toList());
        return result;
    }

    /**
     * Returns safe aggregate AI diagnostics visible to this MCP subject.
     */
    private Object getAiDiagnosticSummary(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
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
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String query = stringArgument(arguments, "query");
        int limit = intArgument(arguments, "limit", 10);
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required for search_teacher_resource_evidence");
        }
        List<String> librarySelectors = mergeDistinct(
                flexibleStringListArgument(arguments, "libraries", "library"),
                flexibleStringListArgument(arguments, "sourceTypes", "sourceType"));
        TeacherResourceBlockSearchResponse response = teacherResourceBlockSearchService.search(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                query,
                limit,
                "/api/mcp/tools/search_teacher_resource_evidence/call",
                TeacherResourceSearchFilter.of(
                        flexibleStringListArgument(arguments, "permissionScopes", "permissionScope"),
                        flexibleStringListArgument(arguments, "documentIds", "documentId"),
                        librarySelectors,
                        flexibleStringListArgument(arguments, "tags", "tag")));
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
     * Discovers remote Feishu resources through the configured real Feishu discovery client.
     */
    private Object discoverFeishuResources(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        TeacherFeishuDiscoveryResponse response = teacherFeishuDiscoveryService.discover(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                stringArgumentOrDefault(arguments, "mode", "list"),
                stringArgument(arguments, "keyword"),
                stringArgument(arguments, "rootUrl"),
                intArgument(arguments, "listDepth", 1),
                intArgument(arguments, "maxDepth", 5));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryId", response.queryId());
        result.put("mode", response.mode());
        result.put("rootUrl", response.rootUrl());
        result.put("keyword", response.keyword());
        result.put("depth", response.depth());
        result.put("candidateCount", response.candidateCount());
        result.put("candidates", response.candidates());
        result.put("status", response.status());
        result.put("message", response.message());
        return result;
    }

    /**
     * Registers, downloads, parses, and stores one Feishu resource through the existing teacher sync pipeline.
     */
    private Object downloadFeishuResource(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String url = stringArgument(arguments, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url is required for download_feishu_resource");
        }
        TeacherResourceDocumentResponse document = teacherResourceService.register(new TeacherResourceRegistrationCommand(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                "feishu",
                stringArgumentOrDefault(arguments, "title", "MCP Feishu resource"),
                url,
                null,
                "TEACHER_PRIVATE",
                stringArgumentOrDefault(arguments, "exportFormat", "md")));
        TeacherSourceSyncJobResponse queued = teacherSourceSyncJobService.createSyncJob(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                document.documentId());
        TeacherSourceSyncJobResponse executed = teacherSourceSyncExecutionService.execute(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                document.documentId(),
                queued.jobId());
        TeacherResourceDocumentResponse latestDocument = teacherResourceService.list(
                        client.tenantId(),
                        normalizedProfile(client.profile()),
                        client.subjectId())
                .stream()
                .filter(candidate -> candidate.documentId().equals(document.documentId()))
                .findFirst()
                .orElse(document);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", document.documentId());
        result.put("jobId", executed.jobId());
        result.put("status", executed.status());
        result.put("phase", executed.phase());
        result.put("message", executed.message());
        result.put("stagingPath", executed.stagingPath());
        result.put("syncStatus", latestDocument.syncStatus());
        result.put("parseStatus", latestDocument.parseStatus());
        result.put("embeddingStatus", latestDocument.embeddingStatus());
        result.put("feishuExportFormat", latestDocument.feishuExportFormat());
        result.put("previewFiles", latestDocument.previewFiles());
        return result;
    }

    /**
     * Returns a safe CoursewareAgent trace linked to a teaching task id visible to this MCP subject.
     */
    private Object getTeachingAiTrace(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
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
     * Starts a resumable multi-agent writing workflow for WorkBuddy through backend-owned identity.
     */
    private Object startMultiAgentWriting(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        MultiAgentWritingResponse response = multiAgentWritingService.startAsync(
                multiAgentWritingRequest(request),
                requestSubject(client));
        return multiAgentWritingResult(response);
    }

    /**
     * Reads the latest workflow status so external MCP clients can recover after disconnects.
     */
    private Object getMultiAgentWritingStatus(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String workflowId = normalizedWorkflowId(stringArgument(arguments, "workflowId"));
        MultiAgentWritingResponse response = multiAgentWritingService.find(workflowId, requestSubject(client))
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        return multiAgentWritingResult(response);
    }

    /**
     * Reads owner-visible generated writing content for a completed or running workflow.
     */
    private Object getMultiAgentWritingArtifact(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String workflowId = normalizedWorkflowId(stringArgument(arguments, "workflowId"));
        MultiAgentWritingArtifact artifact = multiAgentWritingService.artifact(workflowId, requestSubject(client));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", artifact.workflowId());
        result.put("tenantId", artifact.tenantId());
        result.put("subjectType", artifact.subjectType());
        result.put("subjectId", artifact.subjectId());
        result.put("status", artifact.status());
        result.put("totalUsage", artifact.totalUsage());
        result.put("stages", artifact.stages());
        result.put("mergedMarkdown", artifact.mergedMarkdown());
        return result;
    }

    /**
     * Exports owner-visible generated writing content as Markdown or ZIP bytes for MCP clients.
     */
    private Object exportMultiAgentWritingArtifact(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String workflowId = normalizedWorkflowId(stringArgument(arguments, "workflowId"));
        MultiAgentWritingArtifactExportResponse response = multiAgentWritingArtifactExportService.export(
                workflowId,
                stringArgumentOrDefault(arguments, "format", "markdown"),
                requestSubject(client));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportId", response.exportId());
        result.put("workflowId", response.workflowId());
        result.put("format", response.format());
        result.put("fileName", response.fileName());
        result.put("mimeType", response.mimeType());
        result.put("byteSize", response.byteSize());
        result.put("sha256", response.sha256());
        result.put("base64Content", response.base64Content());
        result.put("expiresAt", response.expiresAt());
        return result;
    }

    /**
     * Resumes a failed workflow from the first incomplete stage using the saved workflow owner.
     */
    private Object resumeMultiAgentWriting(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String workflowId = normalizedWorkflowId(stringArgument(arguments, "workflowId"));
        MultiAgentWritingResponse response = multiAgentWritingService.resume(
                workflowId,
                multiAgentWritingRequest(request),
                requestSubject(client));
        return multiAgentWritingResult(response);
    }

    /**
     * Converts loose MCP JSON arguments into the backend multi-agent writing request.
     */
    private static MultiAgentWritingRequest multiAgentWritingRequest(McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        String questionText = stringArgumentOrDefault(
                arguments,
                "questionText",
                stringArgumentOrDefault(arguments, "question", stringArgument(arguments, "topic")));
        if (questionText.isBlank()) {
            throw new IllegalArgumentException("questionText is required for multi-agent writing");
        }
        return new MultiAgentWritingRequest(
                stringArgumentOrDefault(arguments, "writingGoal", stringArgumentOrDefault(arguments, "goal", "teacher handout")),
                questionText,
                stringListArgument(arguments, "evidenceRefs"),
                false,
                normalizedProviderName(stringArgument(arguments, "preferredProviderName")),
                stringArgument(arguments, "preferredModelCode"));
    }

    /**
     * Builds a compact JSON-friendly workflow result without raw prompt or model output.
     */
    private static Map<String, Object> multiAgentWritingResult(MultiAgentWritingResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", response.workflowId());
        result.put("tenantId", response.tenantId());
        result.put("subjectType", response.subjectType());
        result.put("subjectId", response.subjectId());
        result.put("status", response.status());
        result.put("createdAt", response.createdAt());
        result.put("updatedAt", response.updatedAt());
        result.put("stageCount", response.stages().size());
        result.put("stages", response.stages());
        result.put("totalUsage", response.totalUsage());
        result.put("message", response.message());
        return result;
    }

    /**
     * Builds a server-side agent run plan for WorkBuddy without executing any model or high-value tool.
     */
    private Object planAgentRun(
            McpClientRegistryProperties.Client client,
            McpToolCallRequest request) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        AgentRunPlanResponse plan = agentRunPlanService.plan(
                new AgentRunPlanRequest(
                        stringArgumentOrDefault(
                                arguments,
                                "agentCode",
                                stringArgumentOrDefault(arguments, "agentType", stringArgument(arguments, "agent"))),
                        inferredTaskType(arguments),
                        stringArgumentOrDefault(arguments, "userVipLevel", normalizedProfile(client.profile())),
                        intArgument(arguments, "estimatedInputTokens", 1200),
                        intArgument(arguments, "estimatedOutputTokens", 600),
                        booleanArgument(arguments, "hasImage", false),
                        booleanArgument(arguments, "hasFormula", false),
                        stringArgumentOrDefault(arguments, "difficulty", "medium"),
                        stringArgumentOrDefault(arguments, "latencyRequirement", stringArgumentOrDefault(arguments, "latency", "normal")),
                        doubleArgument(arguments, "costBudget", 1.0d),
                        intArgument(arguments, "previousFailureCount", 0),
                        booleanArgument(arguments, "requiredJsonSchema", false),
                        normalizedToolScopes(arguments, "requestedToolScopes"),
                        normalizedToolScopes(arguments, "disabledToolScopes"),
                        normalizedDataScopesForPlan(arguments),
                        booleanArgument(arguments, "highValueOperation", false),
                        normalizedProviderName(stringArgument(arguments, "preferredProviderName")),
                        stringArgument(arguments, "preferredModelCode")),
                requestSubject(client));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", plan.planId());
        result.put("tenantId", plan.tenantId());
        result.put("subjectType", plan.subjectType());
        result.put("subjectId", plan.subjectId());
        result.put("agentCode", plan.agentCode());
        result.put("providerName", plan.providerName());
        result.put("modelCode", plan.modelCode());
        result.put("modelLevel", plan.modelLevel());
        result.put("allowedToolScopes", plan.allowedToolScopes());
        result.put("deniedToolScopes", plan.deniedToolScopes());
        result.put("toolPolicyDecisions", plan.toolPolicyDecisions());
        result.put("allowedDataScopes", plan.allowedDataScopes());
        result.put("deniedDataScopes", plan.deniedDataScopes());
        result.put("capabilityRequired", plan.capabilityRequired());
        result.put("capabilityAction", plan.capabilityAction());
        result.put("maxInputTokens", plan.maxInputTokens());
        result.put("maxOutputTokens", plan.maxOutputTokens());
        result.put("estimatedTotalTokens", plan.estimatedTotalTokens());
        result.put("estimatedCost", plan.estimatedCost());
        result.put("withinBudget", plan.withinBudget());
        result.put("routeReason", plan.routeReason());
        result.put("stageTimings", plan.stageTimings());
        result.put("concurrencyKeys", plan.concurrencyKeys());
        result.put("reactToolPlan", reactToolPlan(plan));
        return result;
    }

    /**
     * Builds a ReAct-oriented tool plan that tells external agents which safe evidence tools may run in parallel.
     */
    private static McpReactToolPlan reactToolPlan(AgentRunPlanResponse plan) {
        List<McpReactToolPlan.Action> parallelEvidenceActions = parallelEvidenceActions(plan.allowedToolScopes());
        List<McpReactToolPlan.Group> groups = new java.util.ArrayList<>();
        if (!parallelEvidenceActions.isEmpty()) {
            groups.add(new McpReactToolPlan.Group(
                    "evidence_parallel",
                    "parallel",
                    List.of(),
                    "Collect allowed evidence before writing or solving.",
                    parallelEvidenceActions,
                    "Merge evidence snippets by source scope and keep tenant/subject boundaries."));
        }
        groups.add(new McpReactToolPlan.Group(
                "reasoning_sequential",
                "sequential",
                parallelEvidenceActions.isEmpty() ? List.of() : List.of("evidence_parallel"),
                "Use retrieved evidence and policy decisions to prepare the next answer or handout stage.",
                List.of(new McpReactToolPlan.Action(
                        "agent:reason",
                        "internal_reasoning",
                        true,
                        "Reasoning is internal to the calling agent and does not grant extra backend permissions.")),
                "Only execute high-value generation after platform capability verification."));
        return new McpReactToolPlan(
                "ReAct",
                !parallelEvidenceActions.isEmpty(),
                List.copyOf(groups),
                "Return a plan first; do not execute high-value tools from MCP without a capability-protected platform call.");
    }

    /**
     * Converts allowed backend tool scopes into ReAct action descriptors that may safely run together.
     */
    private static List<McpReactToolPlan.Action> parallelEvidenceActions(List<String> allowedToolScopes) {
        List<McpReactToolPlan.Action> actions = new java.util.ArrayList<>();
        if (allowedToolScopes.contains("tool:search:textbook") && allowedToolScopes.contains("tool:search:private")) {
            actions.add(new McpReactToolPlan.Action(
                    "tool:search:multi_source",
                    "search_multi_source_evidence",
                    true,
                    "Preferred default: search public textbooks and visible teacher resources together before writing or solving."));
            return List.copyOf(actions);
        }
        if (allowedToolScopes.contains("tool:search:textbook")) {
            actions.add(new McpReactToolPlan.Action(
                    "tool:search:textbook",
                    "search_textbook_evidence",
                    true,
                    "Public textbook evidence is read-only and can be collected in parallel."));
        }
        if (allowedToolScopes.contains("tool:search:private")) {
            actions.add(new McpReactToolPlan.Action(
                    "tool:search:private",
                    "search_teacher_resource_evidence",
                    true,
                    "Teacher-private evidence is read-only but remains tenant and subject scoped."));
        }
        return List.copyOf(actions);
    }

    private static SourceSelection sourceSelection(Map<String, Object> arguments) {
        List<String> libraries = flexibleStringListArgument(arguments, "libraries", "library").stream()
                .map(value -> value.strip().toLowerCase())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (libraries.isEmpty()) {
            /*
             * The default AI-facing path should search the teaching libraries we actually expect in classroom RAG,
             * not every legacy local_path package that happens to be visible in the same tenant. Callers that need the
             * broader corpus can still request teacher_resource explicitly.
             */
            return new SourceSelection(
                    true,
                    true,
                    List.of("qq_bundle", "feishu", "gaokao", "mock_exam"),
                    List.of("textbook", "qq_bundle", "feishu", "gaokao", "mock_exam"));
        }
        boolean includeTextbook = libraries.stream().anyMatch(McpToolExecutionService::isTextbookLibrary);
        List<String> teacherSourceTypes = libraries.stream()
                .map(McpToolExecutionService::teacherSourceTypeSelector)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        boolean includeTeacherResources = libraries.stream().anyMatch(McpToolExecutionService::isTeacherResourceLibrary)
                || !teacherSourceTypes.isEmpty();
        return new SourceSelection(includeTextbook, includeTeacherResources, teacherSourceTypes, libraries);
    }

    private static boolean isTextbookLibrary(String library) {
        return "textbook".equals(library) || "public_textbook".equals(library);
    }

    private static boolean isTeacherResourceLibrary(String library) {
        return "teacher_resource".equals(library)
                || "teacher_private".equals(library)
                || "shared_resource".equals(library)
                || "feishu".equals(library)
                || "qq_bundle".equals(library)
                || "gaokao".equals(library)
                || "mock_exam".equals(library)
                || "public_textbook_derivative".equals(library);
    }

    private static String teacherSourceTypeSelector(String library) {
        return switch (library) {
            case "feishu", "qq_bundle", "gaokao", "mock_exam", "public_textbook_derivative" -> library;
            default -> "";
        };
    }

    private static List<Map<String, Object>> mergedEvidenceHits(
            List<TextbookSearchHit> textbookHits,
            List<TeacherResourceBlockSearchResponse.Hit> teacherHits,
            int limit) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (int index = 0; index < textbookHits.size(); index++) {
            TextbookSearchHit hit = textbookHits.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", "textbook");
            row.put("sourceType", "public_textbook");
            row.put("rankInSource", index + 1);
            row.put("mergedScore", reciprocalRankScore(index));
            row.put("title", hit.bookName());
            row.put("documentId", hit.docId());
            row.put("chunkId", hit.chunkId());
            row.put("pageNo", hit.pageNo());
            row.put("sectionTitle", hit.sectionTitle());
            row.put("permissionScope", "PUBLIC_TEXTBOOK");
            row.put("snippet", hit.textSnippet());
            row.put("sourcePageImage", hit.sourcePageImage());
            row.put("pageImageUri", hit.pageImageUri());
            row.put("evidenceRef", "PUBLIC_TEXTBOOK:" + hit.docId() + ":" + hit.chunkId());
            row.put("rawScore", hit.score());
            merged.add(row);
        }
        for (int index = 0; index < teacherHits.size(); index++) {
            TeacherResourceBlockSearchResponse.Hit hit = teacherHits.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", "teacher_resource");
            row.put("sourceType", hit.sourceType());
            row.put("rankInSource", index + 1);
            row.put("mergedScore", reciprocalRankScore(index));
            row.put("title", hit.documentTitle());
            row.put("documentId", hit.documentId());
            row.put("blockId", hit.blockId());
            row.put("pageNo", hit.pageNo());
            row.put("sectionTitle", hit.section());
            row.put("permissionScope", hit.permissionScope());
            row.put("sourcePath", hit.sourcePath());
            row.put("blockRole", hit.blockRole());
            row.put("snippet", hit.snippet());
            row.put("evidenceText", hit.evidenceText());
            row.put("imageAssetIds", hit.imageAssetIds());
            row.put("assetRefs", hit.assetRefs());
            row.put("evidenceRef", hit.permissionScope() + ":" + hit.documentId() + ":" + hit.blockId());
            row.put("rawScore", hit.score());
            merged.add(row);
        }
        return merged.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> row) -> (Double) row.get("mergedScore")).reversed()
                        .thenComparing(row -> String.valueOf(row.get("source")))
                        .thenComparing(row -> String.valueOf(row.get("documentId"))))
                .limit(Math.max(1, limit))
                .toList();
    }

    private static double reciprocalRankScore(int zeroBasedRank) {
        return Math.round((1.0d / (60.0d + zeroBasedRank + 1.0d)) * 1_000_000.0d) / 1_000_000.0d;
    }

    private record SourceSelection(
            boolean includeTextbook,
            boolean includeTeacherResources,
            List<String> teacherSourceTypes,
            List<String> libraries) {
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
     * Enforces registry-level scopes after the exact tool allow-list check.
     */
    private static void requireToolScope(McpClientRegistryProperties.Client client, String toolName) {
        if (MULTI_SOURCE_EVIDENCE_TOOL.equals(toolName)) {
            requireAllScopes(client, List.of("PUBLIC_TEXTBOOK", "teacher-resource:read"));
            return;
        }
        String requiredScope = requiredScope(toolName);
        if (requiredScope.isBlank()) {
            return;
        }
        if (!client.allowedScopes().contains(requiredScope)) {
            throw new IllegalArgumentException(
                    "MCP scope is not allowed for client " + client.clientId() + ": " + requiredScope);
        }
    }

    private static void requireAllScopes(McpClientRegistryProperties.Client client, List<String> requiredScopes) {
        List<String> missing = requiredScopes.stream()
                .filter(scope -> !client.allowedScopes().contains(scope))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP scope is not allowed for client " + client.clientId() + ": " + String.join(", ", missing));
        }
    }

    /**
     * Maps public MCP tool names to the minimum registry scope required for execution.
     */
    private static String requiredScope(String toolName) {
        return switch (toolName) {
            case MULTI_SOURCE_EVIDENCE_TOOL -> "PUBLIC_TEXTBOOK + teacher-resource:read";
            case TEXTBOOK_EVIDENCE_TOOL -> "PUBLIC_TEXTBOOK";
            case TEACHER_RESOURCE_EVIDENCE_TOOL, DISCOVER_FEISHU_RESOURCES_TOOL -> "teacher-resource:read";
            case DOWNLOAD_FEISHU_RESOURCE_TOOL -> "teacher-resource:sync-execute";
            case TEACHING_AI_TRACE_TOOL, AI_DIAGNOSTIC_SUMMARY_TOOL, MULTI_AGENT_WRITING_TRACE_TOOL -> "agent-trace:read";
            case PLAN_AGENT_RUN_TOOL -> "agent:plan";
            case START_MULTI_AGENT_WRITING_TOOL, RESUME_MULTI_AGENT_WRITING_TOOL -> "agent-writing:execute";
            case GET_MULTI_AGENT_WRITING_STATUS_TOOL, GET_MULTI_AGENT_WRITING_ARTIFACT_TOOL -> "agent-writing:read";
            case EXPORT_MULTI_AGENT_WRITING_ARTIFACT_TOOL -> "agent-writing:export";
            default -> "";
        };
    }

    static boolean toolEnabledForClient(McpClientRegistryProperties.Client client, String toolName) {
        if (!client.allowedTools().contains(toolName)) {
            return false;
        }
        if (MULTI_SOURCE_EVIDENCE_TOOL.equals(toolName)) {
            return client.allowedScopes().contains("PUBLIC_TEXTBOOK")
                    && client.allowedScopes().contains("teacher-resource:read");
        }
        String requiredScope = requiredScope(toolName);
        return requiredScope.isBlank() || client.allowedScopes().contains(requiredScope);
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
     * Reads a string argument and applies a default when blank.
     */
    private static String stringArgumentOrDefault(Map<String, Object> arguments, String key, String defaultValue) {
        String value = stringArgument(arguments, key);
        return value.isBlank() ? defaultValue : value;
    }

    /**
     * Infers a planning task type from standard fields or common AI-generated task prose.
     */
    private static String inferredTaskType(Map<String, Object> arguments) {
        String taskType = stringArgument(arguments, "taskType");
        if (!taskType.isBlank()) {
            return taskType;
        }
        String task = stringArgument(arguments, "task").toLowerCase();
        if (task.contains("handout") || task.contains("courseware") || task.contains("讲义") || task.contains("课件")) {
            return "courseware_generation";
        }
        return "question_solving";
    }

    /**
     * Reads a boolean argument from a JSON-like argument map.
     */
    private static boolean booleanArgument(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value).strip());
    }

    /**
     * Reads a bounded double argument from a JSON-like argument map.
     */
    private static double doubleArgument(Map<String, Object> arguments, String key, double defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            return switch (String.valueOf(value).strip().toLowerCase()) {
                case "low" -> 0.2d;
                case "medium", "normal" -> 1.0d;
                case "high" -> 3.0d;
                default -> throw new IllegalArgumentException(key + " must be a number", exception);
            };
        }
    }

    /**
     * Reads a string array argument while ignoring blank values and non-string items.
     */
    private static List<String> stringListArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(item -> item instanceof String)
                .map(item -> ((String) item).strip())
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> mergeDistinct(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    /**
     * Reads either list or scalar JSON arguments emitted by external ReAct clients.
     */
    private static List<String> flexibleStringListArgument(Map<String, Object> arguments, String pluralKey, String singularKey) {
        List<String> listValues = stringListArgument(arguments, pluralKey);
        if (!listValues.isEmpty()) {
            return listValues;
        }
        Object value = arguments.containsKey(singularKey) ? arguments.get(singularKey) : arguments.get(pluralKey);
        if (value instanceof List<?>) {
            return List.of();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Normalizes common AI-generated tool scope aliases to backend policy scope ids.
     */
    private static List<String> normalizedToolScopes(Map<String, Object> arguments, String key) {
        return stringListArgument(arguments, key).stream()
                .map(McpToolExecutionService::normalizedToolScope)
                .filter(scope -> !scope.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Normalizes one common tool scope alias.
     */
    private static String normalizedToolScope(String scope) {
        return switch (scope.strip().toLowerCase()) {
            case "multi_source_search", "search_multi_source", "search_multi_source_evidence" -> "tool:search:multi_source";
            case "textbook_search", "search_textbook", "search_textbook_evidence" -> "tool:search:textbook";
            case "private_search", "teacher_private_search", "search_teacher_resource_evidence" -> "tool:search:private";
            case "courseware_generate", "generate_courseware", "handout_generate" -> "tool:courseware:generate";
            case "quality_check" -> "tool:quality:check";
            case "handout_format" -> "tool:handout:format";
            case "student_progress_read" -> "tool:student:progress:read";
            case "formula_reasoning", "reasoning" -> "";
            default -> scope.strip();
        };
    }

    /**
     * Normalizes common AI-generated data scope aliases to backend policy scope ids.
     */
    private static List<String> normalizedDataScopes(Map<String, Object> arguments, String key) {
        return stringListArgument(arguments, key).stream()
                .map(McpToolExecutionService::normalizedDataScope)
                .filter(scope -> !scope.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Infers public textbook data scope when an AI requests textbook search but omits data scopes.
     */
    private static List<String> normalizedDataScopesForPlan(Map<String, Object> arguments) {
        List<String> dataScopes = normalizedDataScopes(arguments, "requestedDataScopes");
        if (!dataScopes.isEmpty()) {
            return dataScopes;
        }
        if (normalizedToolScopes(arguments, "requestedToolScopes").contains("tool:search:textbook")) {
            return List.of("PUBLIC_TEXTBOOK");
        }
        return List.of();
    }

    /**
     * Normalizes one common data scope alias.
     */
    private static String normalizedDataScope(String scope) {
        return switch (scope.strip().toLowerCase()) {
            case "textbook_content", "public_textbook", "textbook" -> "PUBLIC_TEXTBOOK";
            case "teacher_private", "private_resource", "teacher_resource" -> "TEACHER_PRIVATE";
            case "class_authorized", "class_resource" -> "CLASS_AUTHORIZED";
            case "student_private" -> "STUDENT_PRIVATE";
            case "math_vip" -> "MATH_VIP";
            default -> scope.strip();
        };
    }

    /**
     * Normalizes common provider aliases generated by external AI clients.
     */
    private static String normalizedProviderName(String providerName) {
        return switch (providerName.strip().toLowerCase()) {
            case "qwen", "qianwen", "通义千问", "千问" -> "dashscope";
            case "doubao", "豆包", "volcengine" -> "ark";
            default -> providerName;
        };
    }

    /**
     * Converts an MCP client key binding into the backend subject used by planner policy.
     */
    private static RequestSubject requestSubject(McpClientRegistryProperties.Client client) {
        return new RequestSubject(
                client.tenantId(),
                normalizedProfile(client.profile()),
                client.subjectId(),
                "mcp:" + client.clientId());
    }

    /**
     * Normalizes MCP profile text to backend subject type values.
     */
    private static String normalizedProfile(String profile) {
        return profile == null || profile.isBlank() ? "teacher" : profile.strip().toLowerCase();
    }
}
