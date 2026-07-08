package com.doob.mathagent.protocol.service;

import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.vo.McpPromptDescriptor;
import com.doob.mathagent.protocol.vo.McpResourceDescriptor;
import com.doob.mathagent.protocol.vo.McpToolCallResponse;
import com.doob.mathagent.protocol.vo.McpToolDescriptor;
import com.doob.mathagent.resources.TextbookCatalogItem;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.resources.TextbookResourceService;
import com.doob.mathagent.resources.TextbookResourceSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal JSON-RPC MCP endpoint service for clients that expect one URL in {@code mcpServers}.
 */
public class McpJsonRpcService {

    public static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ProtocolDiscoveryService discoveryService;
    private final McpToolExecutionService toolExecutionService;
    private final McpClientResolver clientResolver;
    private final TextbookResourceService textbookResourceService;
    private final TextbookResourceProperties textbookResourceProperties;
    private final KnowledgeGraphSpineService knowledgeGraphSpineService;

    /**
     * Creates the JSON-RPC MCP service with application-owned resource readers.
     *
     * @param discoveryService descriptor source for MCP tools, prompts, and resources
     * @param toolExecutionService existing tool executor with subject and allow-list checks
     * @param clientResolver registered MCP client secret resolver
     * @param textbookResourceService textbook summary service for resources/read
     * @param textbookResourceProperties configured processed textbook root
     * @param knowledgeGraphSpineService curated graph spine reader
     */
    public McpJsonRpcService(
            ProtocolDiscoveryService discoveryService,
            McpToolExecutionService toolExecutionService,
            McpClientResolver clientResolver,
            TextbookResourceService textbookResourceService,
            TextbookResourceProperties textbookResourceProperties,
            KnowledgeGraphSpineService knowledgeGraphSpineService) {
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService is required");
        this.toolExecutionService = Objects.requireNonNull(toolExecutionService, "toolExecutionService is required");
        this.clientResolver = Objects.requireNonNull(clientResolver, "clientResolver is required");
        this.textbookResourceService = Objects.requireNonNull(textbookResourceService, "textbookResourceService is required");
        this.textbookResourceProperties = Objects.requireNonNull(textbookResourceProperties, "textbookResourceProperties is required");
        this.knowledgeGraphSpineService = Objects.requireNonNull(
                knowledgeGraphSpineService, "knowledgeGraphSpineService is required");
    }

    /**
     * Handles one JSON-RPC request body from a WorkBuddy-style MCP client.
     *
     * @param authorization Authorization header containing the registered Bearer secret
     * @param request JSON-RPC request object
     * @return JSON-RPC response object, or empty map for notifications
     */
    public Map<String, Object> handle(String authorization, Map<String, Object> request) {
        Map<String, Object> normalized = request == null ? Map.of() : request;
        Object id = normalized.get("id");
        if (!"2.0".equals(stringValue(normalized.get("jsonrpc")))) {
            return error(id, -32600, "JSON-RPC version must be 2.0");
        }
        if (!normalized.containsKey("method") && (normalized.containsKey("result") || normalized.containsKey("error"))) {
            return Map.of();
        }
        String method = stringValue(normalized.get("method"));
        if (method.isBlank()) {
            return error(id, -32600, "JSON-RPC method is required");
        }
        if (!normalized.containsKey("id")) {
            return Map.of();
        }
        try {
            return success(id, switch (method) {
                case "initialize" -> initializeResult(mapValue(normalized.get("params")));
                case "ping" -> Map.of();
                case "tools/list" -> toolsListResult(authorization);
                case "tools/call" -> toolsCallResult(authorization, mapValue(normalized.get("params")));
                case "prompts/list" -> promptsListResult(authorization);
                case "prompts/get" -> promptsGetResult(authorization, mapValue(normalized.get("params")));
                case "resources/list" -> resourcesListResult(authorization);
                case "resources/read" -> resourcesReadResult(authorization, mapValue(normalized.get("params")));
                case "resources/templates/list" -> Map.of("resourceTemplates", List.of());
                default -> throw new UnsupportedOperationException("Unsupported MCP method: " + method);
            });
        } catch (UnsupportedOperationException exception) {
            return error(id, -32601, exception.getMessage());
        } catch (McpResourceNotFoundException exception) {
            return error(id, -32002, exception.getMessage());
        } catch (McpInvalidParamsException exception) {
            return error(id, -32602, exception.getMessage());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return error(id, -32000, exception.getMessage());
        }
    }

    /**
     * Returns true when the HTTP protocol-version header names a version this server can parse.
     */
    public static boolean isSupportedProtocolVersion(String protocolVersion) {
        return protocolVersion == null
                || protocolVersion.isBlank()
                || SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion.strip());
    }

    /**
     * Returns MCP initialize metadata.
     */
    private static Map<String, Object> initializeResult(Map<String, Object> params) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        capabilities.put("prompts", Map.of("listChanged", false));
        capabilities.put("resources", Map.of("listChanged", false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiatedProtocolVersion(stringValue(params.get("protocolVersion"))));
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "math-agent-rag", "title", "Math Agent RAG", "version", "0.1.0"));
        result.put("instructions", "Use the exposed read-only tools to inspect math teaching evidence and multi-agent writing traces.");
        return result;
    }

    /**
     * Negotiates the protocol version according to the MCP lifecycle rules.
     */
    private static String negotiatedProtocolVersion(String requestedVersion) {
        if (SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)) {
            return requestedVersion;
        }
        return LATEST_PROTOCOL_VERSION;
    }

    /**
     * Lists only tools allowed for the registered MCP secret.
     */
    private Map<String, Object> toolsListResult(String authorization) {
        McpClientRegistryProperties.Client client = resolveClient(authorization);
        List<Map<String, Object>> tools = discoveryService.mcpTools().stream()
                .filter(McpToolDescriptor::executionEndpointEnabled)
                .filter(tool -> tool.requiredRoles().contains(stringValue(client.profile()).toLowerCase()))
                .filter(tool -> McpToolExecutionService.toolEnabledForClient(client, tool.name()))
                .map(McpJsonRpcService::toolDescriptor)
                .toList();
        return Map.of("tools", tools);
    }

    /**
     * Executes one MCP tool and converts the structured result to MCP content.
     */
    private Map<String, Object> toolsCallResult(String authorization, Map<String, Object> params) {
        String name = stringValue(params.get("name"));
        if (name.isBlank()) {
            throw new McpInvalidParamsException("MCP tools/call requires params.name");
        }
        Map<String, Object> arguments = mapValue(params.get("arguments"));
        try {
            McpToolCallResponse response = toolExecutionService.callTool(
                    authorization,
                    name,
                    new McpToolCallRequest(arguments));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", toJson(response.result()))));
            if (response.result() instanceof Map<?, ?> structuredContent) {
                result.put("structuredContent", structuredContent);
            }
            result.put("isError", false);
            return result;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (isProtocolToolCallFailure(exception)) {
                throw new McpInvalidParamsException(safeErrorMessage(exception));
            }
            return toolErrorResult(name, exception);
        }
    }

    /**
     * Keeps authentication, authorization, and unknown-tool failures as JSON-RPC errors.
     */
    private static boolean isProtocolToolCallFailure(RuntimeException exception) {
        String message = safeErrorMessage(exception).toLowerCase();
        return message.contains("mcp secret")
                || message.contains("authorization")
                || message.contains("not allowed")
                || message.contains("no backend executor");
    }

    /**
     * Converts a failed tool execution into the standard MCP tool-result envelope.
     */
    private static Map<String, Object> toolErrorResult(String toolName, RuntimeException exception) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of(
                "type", "text",
                "text", "MCP tool execution failed: " + safeErrorMessage(exception))));
        result.put("structuredContent", Map.of(
                "toolName", toolName,
                "errorType", exception.getClass().getSimpleName(),
                "message", safeErrorMessage(exception),
                "retryable", toolErrorRetryable(exception)));
        result.put("isError", true);
        return result;
    }

    /**
     * Marks transient backend state as retryable while leaving validation and authorization errors non-retryable.
     */
    private static boolean toolErrorRetryable(RuntimeException exception) {
        if (exception instanceof IllegalStateException) {
            return true;
        }
        String message = safeErrorMessage(exception).toLowerCase();
        return message.contains("timeout")
                || message.contains("temporar")
                || message.contains("retry")
                || message.contains("unavailable");
    }

    /**
     * Returns a single-line tool error message without stack traces or secrets.
     */
    private static String safeErrorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "tool execution failed"
                : message.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    /**
     * Lists prompt descriptors allowed for the registered client profile.
     */
    private Map<String, Object> promptsListResult(String authorization) {
        McpClientRegistryProperties.Client client = resolveClient(authorization);
        List<Map<String, Object>> prompts = discoveryService.mcpPrompts().stream()
                .filter(prompt -> promptAllowed(prompt, client))
                .map(McpJsonRpcService::promptDescriptor)
                .toList();
        return Map.of("prompts", prompts);
    }

    /**
     * Returns one standard MCP prompt with a text message template.
     */
    private Map<String, Object> promptsGetResult(String authorization, Map<String, Object> params) {
        McpClientRegistryProperties.Client client = resolveClient(authorization);
        String name = stringValue(params.get("name"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("MCP prompts/get requires params.name");
        }
        McpPromptDescriptor prompt = discoveryService.mcpPrompts().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP prompt not found: " + name));
        if (!promptAllowed(prompt, client)) {
            throw new IllegalArgumentException("MCP prompt is not allowed for client profile: " + name);
        }
        Map<String, Object> arguments = mapValue(params.get("arguments"));
        return Map.of(
                "description", prompt.description(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", Map.of(
                                "type", "text",
                                "text", promptText(prompt.name(), arguments)))));
    }

    /**
     * Lists safe application-owned resources allowed for the registered client profile.
     */
    private Map<String, Object> resourcesListResult(String authorization) {
        McpClientRegistryProperties.Client client = resolveClient(authorization);
        List<Map<String, Object>> resources = discoveryService.mcpResources().stream()
                .filter(resource -> resourceAllowed(resource, client))
                .map(McpJsonRpcService::resourceDescriptor)
                .toList();
        return Map.of("resources", resources);
    }

    /**
     * Reads one safe resource by exact URI. Arbitrary file and URL reads are intentionally unsupported.
     */
    private Map<String, Object> resourcesReadResult(String authorization, Map<String, Object> params) {
        McpClientRegistryProperties.Client client = resolveClient(authorization);
        String uri = stringValue(params.get("uri"));
        if (uri.isBlank()) {
            throw new IllegalArgumentException("MCP resources/read requires params.uri");
        }
        McpResourceDescriptor resource = discoveryService.mcpResources().stream()
                .filter(candidate -> candidate.uri().equals(uri))
                .findFirst()
                .orElseThrow(() -> new McpResourceNotFoundException("MCP resource not found"));
        if (!resourceAllowed(resource, client)) {
            throw new IllegalArgumentException("MCP resource is not allowed for client profile: " + uri);
        }
        return Map.of("contents", List.of(Map.of(
                "uri", resource.uri(),
                "mimeType", resource.mimeType(),
                "text", resourceText(resource.uri(), client))));
    }

    /**
     * Converts one internal descriptor into the standard MCP tool shape.
     */
    private static Map<String, Object> toolDescriptor(McpToolDescriptor descriptor) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", descriptor.name());
        tool.put("title", descriptor.title());
        tool.put("description", descriptor.description());
        tool.put("inputSchema", descriptor.inputSchema());
        tool.put("annotations", toolAnnotations(descriptor));
        return tool;
    }

    /**
     * Converts internal risk metadata into standard MCP tool annotations.
     */
    private static Map<String, Object> toolAnnotations(McpToolDescriptor descriptor) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("title", descriptor.title());
        annotations.put("readOnlyHint", descriptor.readOnly());
        annotations.put("destructiveHint", !descriptor.readOnly() && descriptor.requiresCapabilityToken());
        annotations.put("idempotentHint", descriptor.readOnly());
        annotations.put("openWorldHint", descriptor.name().contains("feishu"));
        return annotations;
    }

    /**
     * Converts an internal prompt descriptor into the standard MCP prompt shape.
     */
    private static Map<String, Object> promptDescriptor(McpPromptDescriptor descriptor) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", descriptor.name());
        prompt.put("title", descriptor.title());
        prompt.put("description", descriptor.description());
        prompt.put("arguments", descriptor.arguments().stream()
                .map(argument -> Map.of(
                        "name", argument.name(),
                        "title", argument.title(),
                        "description", argument.description(),
                        "required", argument.required()))
                .toList());
        return prompt;
    }

    /**
     * Converts an internal resource descriptor into the standard MCP resource shape.
     */
    private static Map<String, Object> resourceDescriptor(McpResourceDescriptor descriptor) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", descriptor.uri());
        resource.put("name", descriptor.name());
        resource.put("title", descriptor.title());
        resource.put("description", descriptor.description());
        resource.put("mimeType", descriptor.mimeType());
        return resource;
    }

    /**
     * Checks profile-level prompt visibility for a registered MCP client.
     */
    private static boolean promptAllowed(McpPromptDescriptor descriptor, McpClientRegistryProperties.Client client) {
        String profile = stringValue(client.profile()).toLowerCase();
        return descriptor.allowedProfiles().contains(profile);
    }

    /**
     * Checks profile-level resource visibility for a registered MCP client.
     */
    private static boolean resourceAllowed(McpResourceDescriptor descriptor, McpClientRegistryProperties.Client client) {
        String profile = stringValue(client.profile()).toLowerCase();
        return descriptor.allowedProfiles().contains(profile);
    }

    /**
     * Builds the text payload for an application-owned MCP resource.
     */
    private String resourceText(String uri, McpClientRegistryProperties.Client client) {
        return switch (uri) {
            case "math-agent://textbooks/summary" -> textbookSummaryJson();
            case "math-agent://knowledge/graph-spine/v0.1" -> knowledgeGraphSpineJson(client);
            case "math-agent://protocol/capabilities" -> protocolCapabilitiesJson(client);
            default -> throw new McpResourceNotFoundException("MCP resource not found");
        };
    }

    /**
     * Serializes the curated graph spine visible to the registered MCP client.
     */
    private String knowledgeGraphSpineJson(McpClientRegistryProperties.Client client) {
        return toJson(knowledgeGraphSpineService.displaySpine(
                stringValue(client.tenantId()).isBlank() ? "school-a" : client.tenantId(),
                stringValue(client.profile()).isBlank() ? "student" : client.profile(),
                stringValue(client.subjectId())));
    }

    /**
     * Serializes the configured public textbook summary without exposing raw local file contents.
     */
    private String textbookSummaryJson() {
        TextbookResourceSummary summary = textbookResourceService.summarize(textbookResourceProperties.processedBooksRoot());
        return toJson(Map.of(
                "bookCount", summary.bookCount(),
                "totalChunkCount", summary.totalChunkCount(),
                "totalPageCount", summary.totalPageCount(),
                "books", summary.books().stream()
                        .map(McpJsonRpcService::safeBookSummary)
                        .toList()));
    }

    /**
     * Removes local filesystem paths from one textbook catalog item before exposing it through MCP.
     */
    private static Map<String, Object> safeBookSummary(TextbookCatalogItem item) {
        Map<String, Object> book = new LinkedHashMap<>();
        book.put("docId", item.docId());
        book.put("bookName", item.bookName());
        book.put("volume", item.volume());
        book.put("chunkCount", item.chunkCount());
        book.put("pageCount", item.pageCount());
        book.put("aiOk", item.aiOk());
        return book;
    }

    /**
     * Serializes safe protocol capability metadata for MCP clients.
     */
    private String protocolCapabilitiesJson(McpClientRegistryProperties.Client client) {
        return toJson(Map.of(
                "tools", discoveryService.mcpTools().stream()
                        .filter(McpToolDescriptor::executionEndpointEnabled)
                        .filter(tool -> client.allowedTools().contains(tool.name()))
                        .map(McpToolDescriptor::name)
                        .toList(),
                "prompts", discoveryService.mcpPrompts().stream()
                        .filter(prompt -> promptAllowed(prompt, client))
                        .map(McpPromptDescriptor::name)
                        .toList(),
                "resources", discoveryService.mcpResources().stream()
                        .filter(resource -> resourceAllowed(resource, client))
                        .map(McpResourceDescriptor::uri)
                        .toList()));
    }

    /**
     * Builds a concise prompt body without embedding secrets, raw system prompts, or private identity values.
     */
    private static String promptText(String promptName, Map<String, Object> arguments) {
        String topic = stringValue(arguments.get("topic"));
        String evidence = stringValue(arguments.get("evidence"));
        return switch (promptName) {
            case "teacher_handout_writer" -> """
                    Write a teacher-version high school math handout.
                    Topic: %s
                    Evidence: %s
                    Requirements: cite textbook or teacher-resource evidence, include knowledge point attribution, worked methods, and final answers. Keep wording classroom-ready and avoid AI-style narration.
                    """.formatted(blankFallback(topic, "<topic>"), blankFallback(evidence, "<evidence from tools>")).strip();
            case "student_blank_handout_writer" -> """
                    Write a student-version high school math handout.
                    Topic: %s
                    Evidence: %s
                    Requirements: keep blanks for key steps, provide scaffolding hints, and do not expose teacher-only detailed answers.
                    """.formatted(blankFallback(topic, "<topic>"), blankFallback(evidence, "<evidence from tools>")).strip();
            case "solution_reviewer" -> """
                    Review this high school math solution.
                    Question: %s
                    Solution: %s
                    Evidence: %s
                    Requirements: identify correctness, missing reasoning, knowledge point alignment, and concise revision advice.
                    """.formatted(
                            blankFallback(stringValue(arguments.get("question")), "<question>"),
                            blankFallback(stringValue(arguments.get("solution")), "<solution>"),
                            blankFallback(evidence, "<optional evidence>")).strip();
            default -> throw new IllegalArgumentException("MCP prompt not found: " + promptName);
        };
    }

    /**
     * Returns fallback text when an optional prompt argument is empty.
     */
    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Resolves a registered MCP client from the bearer secret.
     */
    private McpClientRegistryProperties.Client resolveClient(String authorization) {
        String secret = bearerSecret(authorization);
        return clientResolver.findEnabledClientBySecret(secret)
                .orElseThrow(() -> new IllegalArgumentException("MCP client secret is not registered or disabled"));
    }

    /**
     * Returns a JSON-RPC success response.
     */
    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /**
     * Returns a JSON-RPC error response.
     */
    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "MCP request failed" : message));
        return response;
    }

    /**
     * Serializes tool output for MCP text content.
     */
    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize MCP tool result", exception);
        }
    }

    /**
     * Extracts a nested JSON object from an untyped request.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * Converts a JSON value to stripped text.
     */
    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    /**
     * Parses an Authorization Bearer secret without logging it.
     */
    private static String bearerSecret(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("MCP Authorization Bearer secret is required");
        }
        String normalized = authorizationHeader.strip();
        if (!normalized.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new IllegalArgumentException("MCP Authorization must use Bearer secret");
        }
        String secret = normalized.substring("Bearer ".length()).strip();
        if (secret.isBlank()) {
            throw new IllegalArgumentException("MCP Bearer secret is empty");
        }
        return secret;
    }

    /**
     * JSON-RPC error marker for unknown MCP resource URIs.
     */
    private static class McpResourceNotFoundException extends RuntimeException {

        /**
         * Creates a resource-not-found marker.
         */
        McpResourceNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * JSON-RPC invalid-params marker for malformed protocol requests.
     */
    private static class McpInvalidParamsException extends RuntimeException {

        /**
         * Creates an invalid-params marker.
         */
        McpInvalidParamsException(String message) {
            super(message);
        }
    }
}
