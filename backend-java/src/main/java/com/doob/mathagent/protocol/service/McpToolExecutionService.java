package com.doob.mathagent.protocol.service;

import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.vo.McpToolCallResponse;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Executes the small set of MCP tools that are safe to expose to external local clients.
 */
@Service
public class McpToolExecutionService {

    private static final String TEXTBOOK_EVIDENCE_TOOL = "search_textbook_evidence";

    private final McpClientRegistryProperties registryProperties;
    private final TextbookRetrievalService textbookRetrievalService;
    private final TextbookResourceProperties textbookResourceProperties;

    /**
     * Creates an MCP execution service.
     *
     * @param registryProperties registered MCP client secrets and allowed tool scopes
     * @param textbookRetrievalService real textbook retrieval service
     * @param textbookResourceProperties processed textbook resource configuration
     */
    public McpToolExecutionService(
            McpClientRegistryProperties registryProperties,
            TextbookRetrievalService textbookRetrievalService,
            TextbookResourceProperties textbookResourceProperties) {
        this.registryProperties = registryProperties == null ? new McpClientRegistryProperties() : registryProperties;
        this.textbookRetrievalService = textbookRetrievalService;
        this.textbookResourceProperties = textbookResourceProperties;
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
