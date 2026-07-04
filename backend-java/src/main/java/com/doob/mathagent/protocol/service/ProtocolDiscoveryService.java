package com.doob.mathagent.protocol.service;

import com.doob.mathagent.protocol.dto.McpConfigurationRequest;
import com.doob.mathagent.protocol.vo.A2aAgentCardResponse;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import com.doob.mathagent.protocol.vo.McpPromptDescriptor;
import com.doob.mathagent.protocol.vo.McpResourceDescriptor;
import com.doob.mathagent.protocol.vo.McpToolDescriptor;
import com.doob.mathagent.protocol.vo.McpToolInputSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Provides read-only MCP and A2A discovery metadata.
 */
@Service
public class ProtocolDiscoveryService {

    private static final List<String> TEACHING_ROLES = List.of("student", "teacher", "admin");
    private static final List<String> TEACHER_ROLES = List.of("teacher", "admin");
    private static final List<String> ALL_PROMPTS = List.of(
            "teacher_handout_writer",
            "student_blank_handout_writer",
            "solution_reviewer");
    private static final List<String> STUDENT_TOOLS = List.of(
            "search_textbook_evidence",
            "get_teaching_ai_trace",
            "get_ai_diagnostic_summary",
            "plan_agent_run");
    private static final List<String> STUDENT_CONFIGURABLE_TOOLS = List.of(
            "search_textbook_evidence",
            "get_teaching_ai_trace",
            "get_ai_diagnostic_summary");
    private static final List<String> STUDENT_PROMPTS = List.of("student_blank_handout_writer", "solution_reviewer");
    private static final List<String> TEACHER_TOOLS = List.of(
            "search_textbook_evidence",
            "search_teacher_resource_evidence",
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
    private static final List<String> TEACHER_CONFIGURABLE_TOOLS = List.of(
            "search_textbook_evidence",
            "search_teacher_resource_evidence",
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
    private static final List<String> TEACHER_PROMPTS = ALL_PROMPTS;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpClientRegistryProperties clientRegistryProperties;

    /**
     * Creates the protocol discovery service with an empty MCP client registry.
     */
    public ProtocolDiscoveryService() {
        this(new McpClientRegistryProperties());
    }

    /**
     * Creates the protocol discovery service with configured MCP client profiles.
     *
     * @param clientRegistryProperties registered MCP client hashes and profiles
     */
    public ProtocolDiscoveryService(McpClientRegistryProperties clientRegistryProperties) {
        this.clientRegistryProperties = clientRegistryProperties == null
                ? new McpClientRegistryProperties()
                : clientRegistryProperties;
    }

    /**
     * Returns MCP tool descriptors without exposing execution endpoints.
     */
    public List<McpToolDescriptor> mcpTools() {
        return List.of(
                new McpToolDescriptor(
                        "search_textbook_evidence",
                        "Search textbook evidence",
                        "Search public textbook evidence with backend tenant and role checks.",
                        true,
                        true,
                        TEACHING_ROLES,
                        "query:basic",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("query", "string", "Search text submitted by the agent."),
                                        field("limit", "integer", "Maximum evidence snippets to return.")),
                                "query")),
                new McpToolDescriptor(
                        "search_teacher_resource_evidence",
                        "Search teacher resource evidence",
                        "Search parsed Feishu and teacher resource blocks visible to the registered teacher/admin key.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "teacher-resource:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("query", "string", "Search text submitted by the agent."),
                                        field("limit", "integer", "Maximum evidence snippets to return.")),
                                "query")),
                new McpToolDescriptor(
                        "get_teaching_ai_trace",
                        "Get teaching AI trace",
                        "Read the safe CoursewareAgent trace linked to an owned teaching task id.",
                        true,
                        true,
                        TEACHING_ROLES,
                        "agent-trace:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(field("taskId", "string", "Owned teaching task id linked as trace planId.")),
                                "taskId")),
                new McpToolDescriptor(
                        "get_ai_diagnostic_summary",
                        "Get AI diagnostic summary",
                        "Read aggregate retry, JSON parse, and provider fallback diagnostics visible to the registered key.",
                        true,
                        true,
                        TEACHING_ROLES,
                        "agent-trace:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("agentCode", "string", "Optional backend agent code filter."),
                                        field("status", "string", "Optional trace status filter."),
                                        field("limit", "integer", "Maximum visible trace rows to aggregate.")))),
                new McpToolDescriptor(
                        "get_multi_agent_writing_trace",
                        "Get multi-agent writing trace",
                        "Read safe ordered traces for a visible teacher multi-agent writing workflow.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "agent-trace:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(field("workflowId", "string", "Workflow id returned by multi-agent writing.")),
                                "workflowId")),
                new McpToolDescriptor(
                        "plan_agent_run",
                        "Plan agent run",
                        "Plan a teaching agent run, route provider/model, and return ReAct/parallel tool guidance without executing high-value actions.",
                        true,
                        true,
                        TEACHING_ROLES,
                        "agent:plan",
                        "medium",
                        false,
                        true,
                        schema(
                                fields(
                                        field("agentCode", "string", "Backend agent code to plan."),
                                        field("agent", "string", "Alias for agentCode when generated by an external AI client."),
                                        field("task", "string", "Natural-language task. Courseware or handout text is inferred as courseware_generation."),
                                        field("taskType", "string", "Backend task type, for example courseware_generation or question_solving."),
                                        field("preferredProviderName", "string", "Optional provider preference such as dashscope, openai, deepseek, or ark."),
                                        field("preferredModelCode", "string", "Optional model code configured on the backend."),
                                        field("estimatedInputTokens", "integer", "Estimated prompt/input tokens for budget and route planning."),
                                        field("estimatedOutputTokens", "integer", "Estimated completion/output tokens for budget and route planning."),
                                        field("costBudget", "number", "Budget hint. Numeric values are preferred; low, medium, and high aliases are accepted."),
                                        field("hasFormula", "boolean", "Whether the request contains formula-heavy reasoning."),
                                        field("requiredJsonSchema", "boolean", "Whether the downstream model output must be strict JSON."),
                                        fieldArray("requestedToolScopes", "Tool scopes requested by the client, for example tool:search:textbook or textbook_search."),
                                        fieldArray("disabledToolScopes", "Tool scopes disabled by the user."),
                                        fieldArray("requestedDataScopes", "Data scopes requested by the client, for example PUBLIC_TEXTBOOK or TEACHER_PRIVATE.")))),
                new McpToolDescriptor(
                        "start_multi_agent_writing",
                        "Start multi-agent writing",
                        "Start a resumable teacher handout writing workflow through backend DAG/ReAct planning and real model execution.",
                        false,
                        true,
                        TEACHER_ROLES,
                        "agent-writing:execute",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("writingGoal", "string", "Writing goal, for example teacher handout or student blank handout."),
                                        field("questionText", "string", "Math topic, question, or teaching objective to write around."),
                                        field("question", "string", "Alias for questionText when used by external agents."),
                                        fieldArray("evidenceRefs", "Evidence references returned by textbook or teacher-resource search."),
                                        field("preferredProviderName", "string", "Optional provider preference such as dashscope, openai, deepseek, or ark."),
                                        field("preferredModelCode", "string", "Optional backend-configured model code.")),
                                "questionText")),
                new McpToolDescriptor(
                        "get_multi_agent_writing_status",
                        "Get multi-agent writing status",
                        "Read the latest visible workflow status so WorkBuddy can recover after page refreshes or network interruption.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "agent-writing:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(field("workflowId", "string", "Workflow id returned by start_multi_agent_writing.")),
                                "workflowId")),
                new McpToolDescriptor(
                        "get_multi_agent_writing_artifact",
                        "Get multi-agent writing artifact",
                        "Read owner-visible generated handout draft content from a multi-agent writing workflow.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "agent-writing:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(field("workflowId", "string", "Workflow id returned by start_multi_agent_writing.")),
                                "workflowId")),
                new McpToolDescriptor(
                        "export_multi_agent_writing_artifact",
                        "Export multi-agent writing artifact",
                        "Export owner-visible generated handout content as UTF-8 Markdown, LaTeX, or ZIP bytes for MCP clients.",
                        false,
                        true,
                        TEACHER_ROLES,
                        "agent-writing:export",
                        "medium",
                        false,
                        true,
                        schema(
                                fields(
                                        field("workflowId", "string", "Workflow id returned by start_multi_agent_writing."),
                                        field("format", "string", "Export format: markdown, md, latex, tex, or zip.")),
                                "workflowId")),
                new McpToolDescriptor(
                        "resume_multi_agent_writing",
                        "Resume multi-agent writing",
                        "Resume a failed teacher writing workflow from the first incomplete DAG stage.",
                        false,
                        true,
                        TEACHER_ROLES,
                        "agent-writing:execute",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("workflowId", "string", "Failed workflow id to resume."),
                                        field("writingGoal", "string", "Writing goal for remaining stages."),
                                        field("questionText", "string", "Math topic, question, or teaching objective."),
                                        fieldArray("evidenceRefs", "Evidence references returned by prior searches."),
                                        field("preferredProviderName", "string", "Optional provider preference."),
                                        field("preferredModelCode", "string", "Optional backend-configured model code.")),
                                "workflowId",
                                "questionText")),
                new McpToolDescriptor(
                        "discover_feishu_resources",
                        "Discover Feishu resources",
                        "List or search Feishu folder candidates through the backend Feishu client without downloading content.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "teacher-resource:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("mode", "string", "Discovery mode: list or search."),
                                        field("keyword", "string", "Search keyword when mode is search."),
                                        field("rootUrl", "string", "Required Feishu folder URL supplied by the caller."),
                                        field("listDepth", "integer", "Folder list depth; backend clamps the value."),
                                        field("maxDepth", "integer", "Search depth; backend clamps the value.")))),
                new McpToolDescriptor(
                        "download_feishu_resource",
                        "Download Feishu resource",
                        "Register, download, parse, and store one Feishu resource into the backend configured staging root.",
                        false,
                        true,
                        TEACHER_ROLES,
                        "teacher-resource:sync-execute",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("url", "string", "Feishu document or folder URL to download."),
                                        field("title", "string", "Display title for the registered teacher resource."),
                                        field("exportFormat", "string", "Native export format: md, docx, or pdf.")),
                                "url")));
    }

    /**
     * Returns standard MCP prompt descriptors. Prompt bodies are intentionally concise templates,
     * not hidden model chains; callers still need backend tools for evidence.
     */
    public List<McpPromptDescriptor> mcpPrompts() {
        return List.of(
                new McpPromptDescriptor(
                        "teacher_handout_writer",
                        "Teacher handout writer",
                        "Write a teacher-version math handout with evidence, method attribution, and worked solution structure.",
                        TEACHER_ROLES,
                        List.of(
                                promptArgument("topic", "Topic", "Teaching topic or knowledge point.", true),
                                promptArgument("evidence", "Evidence", "Textbook or Feishu evidence snippets to ground the handout.", false),
                                promptArgument("difficulty", "Difficulty", "Target difficulty or student level.", false))),
                new McpPromptDescriptor(
                        "student_blank_handout_writer",
                        "Student blank handout writer",
                        "Write a student-version handout with blanks, scaffolded steps, and no teacher-only answers.",
                        TEACHING_ROLES,
                        List.of(
                                promptArgument("topic", "Topic", "Teaching topic or question focus.", true),
                                promptArgument("evidence", "Evidence", "Evidence snippets that should appear as source hints.", false))),
                new McpPromptDescriptor(
                        "solution_reviewer",
                        "Solution reviewer",
                        "Review a math solution for correctness, missing reasoning, and knowledge point alignment.",
                        TEACHING_ROLES,
                        List.of(
                                promptArgument("question", "Question", "Original math question.", true),
                                promptArgument("solution", "Solution", "Student or agent solution to review.", true),
                                promptArgument("evidence", "Evidence", "Optional textbook or teacher-resource evidence.", false))));
    }

    /**
     * Returns safe MCP resources. Resource URIs are application-owned and never map directly to local file paths.
     */
    public List<McpResourceDescriptor> mcpResources() {
        return List.of(
                new McpResourceDescriptor(
                        "math-agent://textbooks/summary",
                        "textbook_summary",
                        "Textbook corpus summary",
                        "Public processed textbook corpus summary with counts and book metadata.",
                        "application/json",
                        TEACHING_ROLES),
                new McpResourceDescriptor(
                        "math-agent://knowledge/graph-spine/v0.1",
                        "knowledge_graph_spine_v01",
                        "Knowledge graph display spine v0.1",
                        "Curated small high-school math graph for frontend display and RAG routing priors.",
                        "application/json",
                        TEACHING_ROLES),
                new McpResourceDescriptor(
                        "math-agent://protocol/capabilities",
                        "protocol_capabilities",
                        "MCP protocol capabilities",
                        "Safe summary of exposed MCP tools, prompts, and protected capabilities.",
                        "application/json",
                        TEACHING_ROLES));
    }

    /**
     * Returns the A2A Agent Card metadata for this platform.
     */
    public A2aAgentCardResponse a2aAgentCard() {
        return new A2aAgentCardResponse(
                "Math Agent RAG",
                "Math teaching RAG platform for textbook evidence retrieval, teaching task planning, handout generation, and student learning support.",
                "/api/a2a",
                "0.3.0",
                "jsonrpc",
                new A2aAgentCardResponse.Capabilities(false, false, true),
                List.of(
                        skill(
                                "textbook_evidence_retrieval",
                                "Textbook Evidence Retrieval",
                                "Retrieves textbook evidence with backend role and tenant boundaries.",
                                "rag",
                                "textbook"),
                        skill(
                                "teaching_task_planning",
                                "Teaching Task Planning",
                                "Plans resumable DAG and ReAct teaching workflows.",
                                "dag",
                                "react"),
                        skill(
                                "teacher_student_handout_generation",
                                "Teacher and Student Handout Generation",
                                "Describes teacher and student handout generation capability with protected exports.",
                                "latex",
                                "pdf",
                                "handout"),
                        skill(
                                "agent_run_planning",
                                "Agent Run Planning",
                                "Plans multi-agent runs and applies backend tool preference policy.",
                                "agent",
                                "tool-policy")),
                List.of(
                        new A2aAgentCardResponse.SecurityScheme(
                                "sa-token-session",
                                "session",
                                "Authenticated platform users are resolved by backend Sa-Token session state."),
                        new A2aAgentCardResponse.SecurityScheme(
                                "capability-token",
                                "one-time-token",
                                "High-value operations require request-hash-bound one-time capability tokens and audit.")));
    }

    /**
     * Validates MCP URL and secret shape, then builds a copyable JSON template without echoing the secret.
     */
    public McpConfigurationResponse mcpConfiguration(McpConfigurationRequest request) {
        String url = normalizeUrl(request.url());
        String secretKey = normalizeSecretKey(request.secretKey());
        String secretEnvName = normalizeSecretEnvName(request.secretEnvName());
        String keyProfile = registeredKeyProfile(secretKey, clientRegistryProperties)
                .orElseThrow(() -> new IllegalArgumentException("secretKey is not registered or enabled"));
        List<String> exposedTools = exposedItems(
                safeList(request.enabledToolNames()),
                "student".equals(keyProfile) ? STUDENT_CONFIGURABLE_TOOLS : TEACHER_CONFIGURABLE_TOOLS);
        List<String> exposedPrompts = exposedItems(
                safeList(request.enabledPromptNames()),
                "student".equals(keyProfile) ? STUDENT_PROMPTS : TEACHER_PROMPTS);
        String configJson = mcpConfigJson(url, secretEnvName, exposedTools, exposedPrompts);
        return new McpConfigurationResponse(
                "math-agent-rag",
                url,
                true,
                true,
                previewSecret(secretKey),
                secretEnvName,
                keyProfile,
                exposedTools,
                exposedPrompts,
                configJson,
                mcpLayers());
    }

    /**
     * Normalizes and validates the externally reachable MCP base URL.
     */
    private static String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MCP URL is required");
        }
        try {
            URI uri = new URI(value.strip()).normalize();
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("MCP URL must use http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("MCP URL must include a host");
            }
            return uri.toString().replaceAll("/+$", "");
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("MCP URL is invalid", exception);
        }
    }

    /**
     * Validates the submitted secret key without storing or returning it.
     */
    private static String normalizeSecretKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("secretKey is required");
        }
        String normalized = value.strip();
        if (normalized.length() < 16 || normalized.length() > 160) {
            throw new IllegalArgumentException("secretKey length must be between 16 and 160");
        }
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("secretKey contains unsupported characters");
        }
        return normalized;
    }

    /**
     * Normalizes the environment variable name used by generated MCP JSON.
     */
    private static String normalizeSecretEnvName(String value) {
        String normalized = value == null || value.isBlank() ? "MATH_AGENT_MCP_SECRET" : value.strip();
        if (!normalized.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("secretEnvName must be an uppercase environment variable name");
        }
        return normalized;
    }

    /**
     * Builds pretty JSON for client MCP configuration.
     */
    private static String mcpConfigJson(
            String url,
            String secretEnvName,
            List<String> exposedTools,
            List<String> exposedPrompts) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "http");
        server.put("url", url);
        server.put("headers", Map.of("Authorization", "Bearer ${" + secretEnvName + "}"));
        Map<String, Object> root = Map.of("mcpServers", Map.of("math-agent-rag", server));
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to build MCP configuration JSON", exception);
        }
    }

    /**
     * Returns a redacted preview that proves validation happened without leaking raw secret.
     */
    private static String previewSecret(String secretKey) {
        return secretKey.substring(0, Math.min(4, secretKey.length()))
                + "..."
                + secretKey.substring(secretKey.length() - 4);
    }

    /**
     * Resolves the profile from the configured secret hash registry.
     */
    private static Optional<String> registeredKeyProfile(
            String secretKey,
            McpClientRegistryProperties registryProperties) {
        String secretHash = McpClientRegistryProperties.secretHash(secretKey);
        return registryProperties.getClients().stream()
                .filter(McpClientRegistryProperties.Client::enabled)
                .filter(client -> secretHash.equalsIgnoreCase(blankToEmpty(client.secretHash())))
                .map(McpClientRegistryProperties.Client::profile)
                .map(ProtocolDiscoveryService::normalizeProfile)
                .filter(profile -> profile.equals("student") || profile.equals("teacher") || profile.equals("admin"))
                .findFirst();
    }

    /**
     * Returns a SHA-256 secret hash for focused tests and local key bootstrap scripts.
     */
    public static String secretHashForTest(String secretKey) {
        return McpClientRegistryProperties.secretHash(secretKey);
    }

    /**
     * Normalizes a configured profile value.
     */
    private static String normalizeProfile(String profile) {
        return profile == null ? "" : profile.strip().toLowerCase();
    }

    /**
     * Converts null strings to empty strings for safe comparisons.
     */
    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Applies backend profile allow-list after user selection; empty selection means all allowed items.
     */
    private static List<String> exposedItems(List<String> requestedItems, List<String> allowedItems) {
        if (requestedItems.isEmpty()) {
            return allowedItems;
        }
        return allowedItems.stream()
                .filter(requestedItems::contains)
                .toList();
    }

    /**
     * Returns a null-safe immutable list.
     */
    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Describes layered MCP access so the frontend can show safe usage guidance.
     */
    private static List<McpConfigurationResponse.Layer> mcpLayers() {
        return List.of(
                new McpConfigurationResponse.Layer(
                        "discovery",
                        "Discovery",
                        "Lists available MCP tools and Agent Card metadata only.",
                        "Sa-Token session or future registered MCP secret",
                        List.of("GET /api/mcp/tools", "GET /api/a2a/.well-known/agent-card.json")),
                new McpConfigurationResponse.Layer(
                        "session",
                        "Session-bound calls",
                        "Uses backend-resolved tenant, role, and subject identity for low-risk read operations.",
                        "Sa-Token session",
                        List.of("textbook evidence search", "teacher resource evidence search")),
                new McpConfigurationResponse.Layer(
                        "high_value",
                        "High-value execution",
                        "Requires a registered MCP secret whose allowed tool list and scopes explicitly enable the operation.",
                        "Registered MCP secret plus backend allow-list scopes",
                        List.of("multi-agent handout writing", "Feishu controlled download", "future protected export tools")));
    }

    /**
     * Creates one A2A skill record.
     */
    private static A2aAgentCardResponse.Skill skill(
            String id,
            String name,
            String description,
            String... tags) {
        return new A2aAgentCardResponse.Skill(id, name, description, List.of(tags));
    }

    /**
     * Creates a JSON schema object for MCP tool arguments.
     */
    private static McpToolInputSchema schema(
            Map<String, Map<String, Object>> properties,
            String... required) {
        return new McpToolInputSchema("object", properties, List.of(required));
    }

    /**
     * Creates a stable ordered field map.
     */
    @SafeVarargs
    private static Map<String, Map<String, Object>> fields(Map.Entry<String, Map<String, Object>>... entries) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            fields.put(entry.getKey(), entry.getValue());
        }
        return fields;
    }

    /**
     * Creates one JSON schema field definition.
     */
    private static Map.Entry<String, Map<String, Object>> field(String name, String type, String description) {
        return Map.entry(name, Map.of("type", type, "description", description));
    }

    /**
     * Creates one JSON schema array field with string items.
     */
    private static Map.Entry<String, Map<String, Object>> fieldArray(String name, String description) {
        return Map.entry(name, Map.of(
                "type", "array",
                "description", description,
                "items", Map.of("type", "string")));
    }

    /**
     * Creates one MCP prompt argument descriptor.
     */
    private static McpPromptDescriptor.Argument promptArgument(
            String name,
            String title,
            String description,
            boolean required) {
        return new McpPromptDescriptor.Argument(name, title, description, required);
    }
}
