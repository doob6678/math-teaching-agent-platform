package com.doob.mathagent.protocol.service;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Provides read-only MCP and A2A discovery metadata plus backend-owned configuration rendering.
 */
@Service
public class ProtocolDiscoveryService {

    private static final List<String> TEACHING_ROLES = List.of("student", "teacher", "admin");
    private static final List<String> TEACHER_ROLES = List.of("teacher", "admin");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> LIBRARY_ENUMS = List.of(
            "textbook",
            "public_textbook",
            "teacher_resource",
            "feishu",
            "qq_bundle",
            "gaokao",
            "mock_exam",
            "public_textbook_derivative");

    /**
     * Creates the protocol discovery service without forcing the MCP resolver graph to initialize.
     *
     * <p>Discovery metadata itself is static. It should stay bootable even while key-management beans are being
     * created, otherwise session subject resolution and MCP key rendering can deadlock the application context during
     * startup.</p>
     */
    public ProtocolDiscoveryService() {
    }

    /**
     * Compatibility constructor kept for existing tests and direct instantiation sites.
     *
     * <p>The resolver argument is intentionally ignored. Runtime discovery does not need it, and reading it here would
     * reintroduce a circular startup dependency through session subject resolution and MCP key services.</p>
     */
    public ProtocolDiscoveryService(McpClientResolver clientResolver) {
    }

    /**
     * Returns MCP tool descriptors.
     */
    public List<McpToolDescriptor> mcpTools() {
        return List.of(
                new McpToolDescriptor(
                        "search_multi_source_evidence",
                        "Search multi-source evidence",
                        "Queries one or more explicitly selected libraries in parallel. The agent must always supply library or libraries; no implicit corpus expansion is allowed.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "PUBLIC_TEXTBOOK + teacher-resource:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("query", "string", "Search text submitted by the agent."),
                                        field("limit", "integer", "Maximum merged evidence snippets to return."),
                                        libraryField(
                                                "library",
                                                "Required single logical library selector when libraries is not supplied."),
                                        libraryArrayField(
                                                "libraries",
                                                "Required logical library selectors. Use one selector per corpus you want the backend to search."),
                                        fieldArray("permissionScopes", "Optional teacher-resource permission scopes such as TEACHER_PRIVATE or MATH_VIP."),
                                        fieldArray("documentIds", "Optional teacher-resource document ids to search."),
                                        fieldArray("sourceTypes", "Optional teacher-resource source types such as feishu, qq_bundle, gaokao, or mock_exam."),
                                        fieldArray("tags", "Optional teacher-resource tags used as retrieval hints.")),
                                "query", "libraries")),
                new McpToolDescriptor(
                        "search_textbook_evidence",
                        "Search textbook evidence",
                        "Search public textbook evidence with backend tenant and role checks.",
                        true,
                        true,
                        TEACHING_ROLES,
                        "PUBLIC_TEXTBOOK",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("query", "string", "Search text submitted by the agent."),
                                        field("limit", "integer", "Maximum evidence snippets to return."),
                                        libraryField("library", "Required textbook selector: textbook or public_textbook.")),
                                "query", "library")),
                new McpToolDescriptor(
                        "search_teacher_resource_evidence",
                        "Search teacher resource evidence",
                        "Search parsed teacher-resource blocks in explicitly selected libraries. The agent must supply library or libraries for every request.",
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
                                        field("limit", "integer", "Maximum evidence snippets to return."),
                                        libraryField(
                                                "library",
                                                "Required single logical library selector when libraries is not supplied."),
                                        libraryArrayField(
                                                "libraries",
                                                "Required logical library selectors. Use these when one request should search multiple named corpora."),
                                        fieldArray("permissionScopes", "Optional teacher-resource permission scopes such as TEACHER_PRIVATE or MATH_VIP."),
                                        fieldArray("documentIds", "Optional teacher-resource document ids to search."),
                                        fieldArray("sourceTypes", "Optional teacher-resource source types such as feishu, qq_bundle, gaokao, or mock_exam."),
                                        fieldArray("tags", "Optional teacher-resource tags used as retrieval hints.")),
                                "query", "libraries")),
                new McpToolDescriptor(
                        "get_teaching_ai_trace",
                        "Get teaching AI trace",
                        "Read the safe teaching-agent trace linked to an owned teaching task id.",
                        true,
                        true,
                        TEACHING_ROLES,
                        "agent-trace:read",
                        "low",
                        false,
                        true,
                        schema(fields(field("taskId", "string", "Owned teaching task id linked as trace planId.")), "taskId")),
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
                        schema(fields(field("workflowId", "string", "Workflow id returned by multi-agent writing.")), "workflowId")),
                new McpToolDescriptor(
                        "plan_agent_run",
                        "Plan agent run",
                        "Plan a teaching agent run and return route, model, and tool guidance without executing the task.",
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
                                        field("task", "string", "Natural-language task."),
                                        field("taskType", "string", "Backend task type, for example courseware_generation or question_solving."),
                                        field("preferredProviderName", "string", "Optional provider preference such as dashscope, openai, deepseek, or ark."),
                                        field("preferredModelCode", "string", "Optional backend model code."),
                                        field("estimatedInputTokens", "integer", "Estimated prompt or input tokens."),
                                        field("estimatedOutputTokens", "integer", "Estimated completion or output tokens."),
                                        field("costBudget", "number", "Budget hint. Numeric values are preferred; low, medium, and high aliases are accepted."),
                                        field("hasFormula", "boolean", "Whether the request contains formula-heavy reasoning."),
                                        field("requiredJsonSchema", "boolean", "Whether the downstream model output must be strict JSON."),
                                        fieldArray("requestedToolScopes", "Tool scopes requested by the client."),
                                        fieldArray("disabledToolScopes", "Tool scopes disabled by the caller."),
                                        fieldArray("requestedDataScopes", "Data scopes requested by the client.")))),
                new McpToolDescriptor(
                        "start_multi_agent_writing",
                        "Start multi-agent writing",
                        "Start a resumable teacher handout writing workflow through real backend execution.",
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
                                        field("preferredModelCode", "string", "Optional backend model code.")),
                                "questionText")),
                new McpToolDescriptor(
                        "get_multi_agent_writing_status",
                        "Get multi-agent writing status",
                        "Read the latest visible workflow status so clients can recover after refresh or interruption.",
                        true,
                        true,
                        TEACHER_ROLES,
                        "agent-writing:read",
                        "low",
                        false,
                        true,
                        schema(fields(field("workflowId", "string", "Workflow id returned by start_multi_agent_writing.")), "workflowId")),
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
                        schema(fields(field("workflowId", "string", "Workflow id returned by start_multi_agent_writing.")), "workflowId")),
                new McpToolDescriptor(
                        "export_multi_agent_writing_artifact",
                        "Export multi-agent writing artifact",
                        "Export owner-visible generated handout content as Markdown, LaTeX, PDF, or ZIP.",
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
                                        field("format", "string", "Export format: markdown, md, latex, tex, pdf, or zip.")),
                                "workflowId")),
                new McpToolDescriptor(
                        "resume_multi_agent_writing",
                        "Resume multi-agent writing",
                        "Resume a failed teacher writing workflow from the first incomplete stage.",
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
                                        field("preferredModelCode", "string", "Optional backend model code.")),
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
                        "Register, download, parse, and store one Feishu resource into the backend staging root.",
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
     * Returns standard MCP prompt descriptors.
     */
    public List<McpPromptDescriptor> mcpPrompts() {
        return List.of(
                new McpPromptDescriptor(
                        "teacher_handout_writer",
                        "Teacher handout writer",
                        "Write a teacher-version math handout with evidence, method attribution, and worked solutions.",
                        TEACHER_ROLES,
                        List.of(
                                promptArgument("topic", "Topic", "Teaching topic or knowledge point.", true),
                                promptArgument("evidence", "Evidence", "Textbook or teacher-resource evidence snippets.", false),
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
     * Returns safe application-owned MCP resources.
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
     * Returns the A2A agent card metadata.
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
     * Builds a copyable MCP configuration for one backend-owned key without trusting frontend identity input.
     */
    public McpConfigurationResponse mcpConfiguration(
            McpClientRegistryProperties.Client client,
            String url,
            String secretEnvName,
            String secretPreview) {
        String normalizedUrl = normalizeUrl(url);
        String normalizedSecretEnvName = normalizeSecretEnvName(secretEnvName);
        String keyProfile = McpAccessPolicy.normalizeProfile(client.profile());
        List<String> exposedTools = visibleToolNames(client);
        List<String> exposedPrompts = visiblePromptNames(keyProfile);
        String configJson = mcpConfigJson(normalizedUrl, normalizedSecretEnvName);
        return new McpConfigurationResponse(
                "math-agent-rag",
                normalizedUrl,
                true,
                true,
                secretPreview,
                normalizedSecretEnvName,
                keyProfile,
                exposedTools,
                exposedPrompts,
                configJson,
                mcpLayers());
    }

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

    private static String normalizeSecretEnvName(String value) {
        String normalized = value == null || value.isBlank() ? "MATH_AGENT_MCP_SECRET" : value.strip();
        if (!normalized.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("secretEnvName must be an uppercase environment variable name");
        }
        return normalized;
    }

    private static String mcpConfigJson(String url, String secretEnvName) {
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
     * Returns a SHA-256 secret hash for focused tests and bootstrap scripts.
     */
    public static String secretHashForTest(String secretKey) {
        return McpClientRegistryProperties.secretHash(secretKey);
    }

    private List<String> visibleToolNames(McpClientRegistryProperties.Client client) {
        return mcpTools().stream()
                .filter(McpToolDescriptor::executionEndpointEnabled)
                .filter(tool -> tool.requiredRoles().contains(McpAccessPolicy.normalizeProfile(client.profile())))
                .filter(tool -> McpToolExecutionService.toolEnabledForClient(client, tool.name()))
                .map(McpToolDescriptor::name)
                .toList();
    }

    private List<String> visiblePromptNames(String keyProfile) {
        return mcpPrompts().stream()
                .filter(prompt -> prompt.allowedProfiles().contains(keyProfile))
                .map(McpPromptDescriptor::name)
                .toList();
    }

    private static List<McpConfigurationResponse.Layer> mcpLayers() {
        return List.of(
                new McpConfigurationResponse.Layer(
                        "discovery",
                        "发现层",
                        "只列出当前账号可见的工具、提示词和 Agent 元数据，不执行高价值动作。",
                        "后端会话或已绑定的 MCP key",
                        List.of("查看工具清单", "查看 Agent Card")),
                new McpConfigurationResponse.Layer(
                        "session",
                        "会话层",
                        "后端使用 Sa-Token 登录态解析租户、角色和主体身份，前端不传身份参数。",
                        "后端登录会话",
                        List.of("生成个人 MCP key", "生成当前账号配置")),
                new McpConfigurationResponse.Layer(
                        "execution",
                        "执行层",
                        "外部客户端通过 Bearer MCP secret 调用真实 MCP 能力，仍受后端角色和资源范围约束。",
                        "后端生成的 MCP key",
                        List.of("教材检索", "教师资源检索", "讲义协作", "飞书资源同步")));
    }

    private static A2aAgentCardResponse.Skill skill(String id, String name, String description, String... tags) {
        return new A2aAgentCardResponse.Skill(id, name, description, List.of(tags));
    }

    private static McpToolInputSchema schema(Map<String, Map<String, Object>> properties, String... required) {
        return new McpToolInputSchema("object", properties, List.of(required));
    }

    @SafeVarargs
    private static Map<String, Map<String, Object>> fields(Map.Entry<String, Map<String, Object>>... entries) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            fields.put(entry.getKey(), entry.getValue());
        }
        return fields;
    }

    private static Map.Entry<String, Map<String, Object>> field(String name, String type, String description) {
        return Map.entry(name, Map.of("type", type, "description", description));
    }

    private static Map.Entry<String, Map<String, Object>> libraryField(String name, String description) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "string");
        definition.put("description", description);
        definition.put("enum", LIBRARY_ENUMS);
        definition.put("x-enum-descriptions", libraryEnumDescriptions());
        return Map.entry(name, definition);
    }

    private static Map.Entry<String, Map<String, Object>> libraryArrayField(String name, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "string");
        item.put("enum", LIBRARY_ENUMS);
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "array");
        definition.put("description", description);
        definition.put("items", item);
        definition.put("x-enum-descriptions", libraryEnumDescriptions());
        return Map.entry(name, definition);
    }

    private static Map.Entry<String, Map<String, Object>> fieldArray(String name, String description) {
        return Map.entry(name, Map.of(
                "type", "array",
                "description", description,
                "items", Map.of("type", "string")));
    }

    private static List<String> libraryEnumDescriptions() {
        List<String> descriptions = new ArrayList<>();
        descriptions.add("textbook: public textbook alias. Use when the request is clearly asking for standard textbook explanations, chapter content, or parsed textbook evidence.");
        descriptions.add("public_textbook: same public textbook corpus as textbook. Prefer textbook unless a client needs the explicit backend corpus name.");
        descriptions.add("teacher_resource: broad visible teacher-resource corpus. Use only when the request does not clearly belong to a narrower library such as feishu, qq_bundle, gaokao, or mock_exam.");
        descriptions.add("feishu: teacher method docs, boardwork logic, classroom reminders, templates, and other Feishu-authored teaching materials.");
        descriptions.add("qq_bundle: QQ topic packages that may mix topic notes, original questions, answers, analyses, and commentary in the same package.");
        descriptions.add("gaokao: real gaokao papers and their parsed question, answer, or analysis blocks.");
        descriptions.add("mock_exam: mock exam papers and their parsed question, answer, or commentary analysis blocks.");
        descriptions.add("public_textbook_derivative: teacher-resource documents derived from public textbook material rather than the textbook corpus itself.");
        return descriptions;
    }

    private static McpPromptDescriptor.Argument promptArgument(String name, String title, String description, boolean required) {
        return new McpPromptDescriptor.Argument(name, title, description, required);
    }
}
