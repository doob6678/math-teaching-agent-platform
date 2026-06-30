package com.doob.mathagent.protocol.service;

import com.doob.mathagent.protocol.dto.McpConfigurationRequest;
import com.doob.mathagent.protocol.vo.A2aAgentCardResponse;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
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
            "plan_agent_run");
    private static final List<String> STUDENT_CONFIGURABLE_TOOLS = List.of(
            "search_textbook_evidence",
            "get_teaching_ai_trace");
    private static final List<String> STUDENT_PROMPTS = List.of("student_blank_handout_writer", "solution_reviewer");
    private static final List<String> TEACHER_TOOLS = List.of(
            "search_textbook_evidence",
            "search_teacher_resource_evidence",
            "get_teaching_ai_trace",
            "plan_agent_run",
            "create_teaching_task",
            "export_handout_pdf",
            "list_teacher_resources");
    private static final List<String> TEACHER_CONFIGURABLE_TOOLS = List.of(
            "search_textbook_evidence",
            "search_teacher_resource_evidence",
            "get_teaching_ai_trace");
    private static final List<String> TEACHER_PROMPTS = ALL_PROMPTS;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpClientRegistryProperties clientRegistryProperties;

    /**
     * Creates the protocol discovery service with an empty local MCP client registry.
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
                        "plan_agent_run",
                        "Plan agent run",
                        "Plan a teaching agent run and apply backend tool policy decisions.",
                        true,
                        false,
                        TEACHING_ROLES,
                        "agent:plan",
                        "medium",
                        false,
                        true,
                        schema(
                                fields(
                                        field("agentCode", "string", "Backend agent code to plan."),
                                        field("requestedToolScopes", "array", "Tool scopes requested by the client."),
                                        field("disabledToolScopes", "array", "Tool scopes disabled by the user.")),
                                "agentCode")),
                new McpToolDescriptor(
                        "create_teaching_task",
                        "Create teaching task",
                        "Create a resumable teaching DAG task after one-time capability verification.",
                        false,
                        false,
                        TEACHING_ROLES,
                        "teaching:write",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("question", "string", "Student question or teaching goal."),
                                        field("goal", "string", "Expected learning objective."),
                                        field("difficulty", "integer", "Target difficulty from 1 to 5.")),
                                "question")),
                new McpToolDescriptor(
                        "export_handout_pdf",
                        "Export handout PDF",
                        "Export an owned teacher or student handout PDF after capability verification.",
                        false,
                        false,
                        TEACHING_ROLES,
                        "teaching:export",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("taskId", "string", "Owned teaching task id."),
                                        field("version", "string", "Handout version: teacher or student.")),
                                "taskId",
                                "version")),
                new McpToolDescriptor(
                        "list_teacher_resources",
                        "List teacher resources",
                        "List teacher-visible resource metadata without raw local file paths.",
                        true,
                        false,
                        TEACHER_ROLES,
                        "teacher-resource:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(field("scope", "string", "Optional resource scope filter.")))));
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
        String keyProfile = keyProfile(secretKey, clientRegistryProperties);
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
        server.put("tools", exposedTools);
        server.put("prompts", exposedPrompts);
        server.put("discovery", Map.of("tools", url + "/tools", "agentCard", url + "/../a2a/.well-known/agent-card.json"));
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
     * Derives a local baseline key profile from the validated secret shape.
     */
    private static String keyProfile(String secretKey, McpClientRegistryProperties registryProperties) {
        Optional<String> registeredProfile = registeredKeyProfile(secretKey, registryProperties);
        if (registeredProfile.isPresent()) {
            return registeredProfile.get();
        }
        return secretKey.startsWith("student_") ? "student" : "teacher";
    }

    /**
     * Resolves the profile from the configured secret hash registry before using local compatibility fallback.
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
                        "Described for future protocol compatibility but excluded from copyable MCP JSON until protected execution is implemented.",
                        "Sa-Token session plus capability token",
                        List.of("teaching task creation", "PDF/ZIP handout export", "model or agent execution")));
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
}
