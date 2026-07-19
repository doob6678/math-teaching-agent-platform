package com.doob.mathagent.protocol.controller;

import com.doob.mathagent.protocol.service.McpJsonRpcService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standard MCP Streamable HTTP endpoint. This is the single URL external MCP clients put in mcpServers.
 */
@RestController
public class McpJsonRpcController {

    private static final String MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final McpJsonRpcService jsonRpcService;

    /**
     * Creates the standard MCP endpoint controller.
     *
     * @param jsonRpcService JSON-RPC MCP handler
     */
    public McpJsonRpcController(McpJsonRpcService jsonRpcService) {
        this.jsonRpcService = jsonRpcService;
    }

    /**
     * Handles one MCP JSON-RPC message over Streamable HTTP.
     */
    @PostMapping(value = "/api/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> post(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        ResponseEntity<Object> rejected = rejectInvalidHttpEnvelope(accept, protocolVersion, request);
        if (rejected != null) {
            return rejected;
        }
        String responseProtocolVersion = responseProtocolVersion(protocolVersion);
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body == null || body.isBlank() ? "" : body);
        } catch (JsonProcessingException exception) {
            return badRequest(responseProtocolVersion, error(null, -32700, "Invalid JSON-RPC JSON body"));
        }
        if (root == null || !root.isObject()) {
            return badRequest(responseProtocolVersion, error(null, -32600, "MCP POST body must be one JSON-RPC object"));
        }
        Map<String, Object> message = OBJECT_MAPPER.convertValue(root, MAP_TYPE);
        responseProtocolVersion = responseProtocolVersion(protocolVersion, message);
        if (initializeProtocolVersionConflicts(protocolVersion, message)) {
            return badRequest(responseProtocolVersion, error(null, -32600, "MCP-Protocol-Version must match initialize.params.protocolVersion"));
        }
        if (isNotificationOrResponse(message)) {
            if (!isValidNotificationOrResponse(message)) {
                return badRequest(responseProtocolVersion, error(null, -32600, "MCP notification or response must be valid JSON-RPC 2.0"));
            }
            return accepted(responseProtocolVersion);
        }
        Map<String, Object> response = jsonRpcService.handle(authorization, message);
        if (response.isEmpty()) {
            return accepted(responseProtocolVersion);
        }
        return ResponseEntity.ok()
                .header(MCP_PROTOCOL_VERSION_HEADER, responseProtocolVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /**
     * Returns 405 because this server does not expose an independent SSE stream.
     */
    @GetMapping("/api/mcp")
    public ResponseEntity<Object> get(
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            HttpServletRequest request) {
        ResponseEntity<Object> rejected = rejectInvalidMethodEnvelope(protocolVersion, request);
        return rejected == null ? methodNotAllowed(protocolVersion) : rejected;
    }

    /**
     * Returns 405 because this stateless endpoint does not support client-terminated sessions.
     */
    @DeleteMapping("/api/mcp")
    public ResponseEntity<Object> delete(
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            HttpServletRequest request) {
        ResponseEntity<Object> rejected = rejectInvalidMethodEnvelope(protocolVersion, request);
        return rejected == null ? methodNotAllowed(protocolVersion) : rejected;
    }

    /**
     * Validates the Streamable HTTP envelope before reading JSON-RPC payloads.
     */
    private static ResponseEntity<Object> rejectInvalidHttpEnvelope(
            String accept,
            String protocolVersion,
            HttpServletRequest request) {
        String responseProtocolVersion = envelopeResponseProtocolVersion(protocolVersion);
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(MCP_PROTOCOL_VERSION_HEADER, responseProtocolVersion)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error(null, -32000, "Origin is not allowed for MCP"));
        }
        if (!accepts(accept, MediaType.APPLICATION_JSON_VALUE) || !accepts(accept, MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                    .header(MCP_PROTOCOL_VERSION_HEADER, responseProtocolVersion)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error(null, -32000, "MCP Accept header must include application/json and text/event-stream"));
        }
        if (!McpJsonRpcService.isSupportedProtocolVersion(protocolVersion)) {
            return badRequest(McpJsonRpcService.LATEST_PROTOCOL_VERSION, error(null, -32000, "Unsupported MCP-Protocol-Version"));
        }
        return null;
    }

    /**
     * Validates non-POST MCP envelopes without applying POST-only Accept rules.
     */
    private static ResponseEntity<Object> rejectInvalidMethodEnvelope(
            String protocolVersion,
            HttpServletRequest request) {
        String responseProtocolVersion = envelopeResponseProtocolVersion(protocolVersion);
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(MCP_PROTOCOL_VERSION_HEADER, responseProtocolVersion)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error(null, -32000, "Origin is not allowed for MCP"));
        }
        if (!McpJsonRpcService.isSupportedProtocolVersion(protocolVersion)) {
            return badRequest(McpJsonRpcService.LATEST_PROTOCOL_VERSION, error(null, -32000, "Unsupported MCP-Protocol-Version"));
        }
        return null;
    }

    /**
     * Chooses a safe protocol header for envelope-level errors before the request is accepted.
     */
    private static String envelopeResponseProtocolVersion(String protocolVersion) {
        return McpJsonRpcService.isSupportedProtocolVersion(protocolVersion)
                ? responseProtocolVersion(protocolVersion)
                : McpJsonRpcService.LATEST_PROTOCOL_VERSION;
    }

    /**
     * Chooses the MCP protocol version echoed in the HTTP response header.
     */
    private static String responseProtocolVersion(String protocolVersion) {
        return protocolVersion == null || protocolVersion.isBlank()
                ? McpJsonRpcService.DEFAULT_PROTOCOL_VERSION
                : protocolVersion.strip();
    }

    /**
     * Uses initialize.params.protocolVersion for the initialize response when the HTTP header is absent.
     */
    private static String responseProtocolVersion(String protocolVersion, Map<String, Object> message) {
        if (protocolVersion != null && !protocolVersion.isBlank()) {
            return protocolVersion.strip();
        }
        if ("initialize".equals(stringValue(message.get("method")))) {
            String requestedVersion = stringValue(mapValue(message.get("params")).get("protocolVersion"));
            if (McpJsonRpcService.isSupportedProtocolVersion(requestedVersion)) {
                return requestedVersion.isBlank()
                        ? McpJsonRpcService.LATEST_PROTOCOL_VERSION
                        : requestedVersion;
            }
            return McpJsonRpcService.LATEST_PROTOCOL_VERSION;
        }
        return McpJsonRpcService.DEFAULT_PROTOCOL_VERSION;
    }

    /**
     * Treats a JSON-RPC notification or client response as accepted input with no response body.
     */
    private static boolean isNotificationOrResponse(Map<String, Object> message) {
        boolean hasMethod = message.containsKey("method");
        boolean hasId = message.containsKey("id");
        return (!hasMethod && (message.containsKey("result") || message.containsKey("error"))) || (hasMethod && !hasId);
    }

    /**
     * Rejects contradictory initialize version signals before returning mismatched header/body metadata.
     */
    private static boolean initializeProtocolVersionConflicts(String protocolVersion, Map<String, Object> message) {
        if (protocolVersion == null || protocolVersion.isBlank() || !"initialize".equals(stringValue(message.get("method")))) {
            return false;
        }
        String requestedVersion = stringValue(mapValue(message.get("params")).get("protocolVersion"));
        return !requestedVersion.isBlank() && !protocolVersion.strip().equals(requestedVersion);
    }

    /**
     * Validates JSON-RPC client notifications and responses before accepting them without a body.
     */
    private static boolean isValidNotificationOrResponse(Map<String, Object> message) {
        if (!"2.0".equals(stringValue(message.get("jsonrpc")))) {
            return false;
        }
        boolean hasMethod = message.containsKey("method");
        boolean hasId = message.containsKey("id");
        if (hasMethod && !hasId) {
            return !stringValue(message.get("method")).isBlank();
        }
        return !hasMethod && hasId && (message.containsKey("result") || message.containsKey("error"));
    }

    /**
     * Applies MCP DNS-rebinding protection while still allowing local WorkBuddy clients.
     */
    private static boolean originAllowed(HttpServletRequest request) {
        if (request == null) {
            return true;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI uri = new URI(origin.strip());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalizedHost = host.strip().toLowerCase();
            String serverName = request.getServerName() == null ? "" : request.getServerName().strip().toLowerCase();
            return normalizedHost.equals(serverName)
                    || normalizedHost.equals("localhost")
                    || normalizedHost.equals("127.0.0.1")
                    || normalizedHost.equals("::1");
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /**
     * Checks one media type token inside an HTTP Accept header.
     */
    private static boolean accepts(String accept, String expectedMediaType) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        for (String rawToken : accept.split(",")) {
            String token = rawToken.split(";", 2)[0].strip().toLowerCase();
            if (token.equals(expectedMediaType) || token.equals("*/*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a JSON-RPC error object.
     */
    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.put("error", Map.of("code", code, "message", message));
        return root;
    }

    /**
     * Returns a JSON bad-request response.
     */
    private static ResponseEntity<Object> badRequest(String responseProtocolVersion, Map<String, Object> body) {
        return ResponseEntity.badRequest()
                .header(MCP_PROTOCOL_VERSION_HEADER, responseProtocolVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Builds a 202 notification response with the negotiated MCP protocol version.
     */
    private static ResponseEntity<Object> accepted(String responseProtocolVersion) {
        return ResponseEntity.accepted()
                .header(MCP_PROTOCOL_VERSION_HEADER, responseProtocolVersion)
                .build();
    }

    /**
     * Builds a 405 response with the methods allowed by the MCP endpoint.
     */
    private static ResponseEntity<Object> methodNotAllowed(String protocolVersion) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(MCP_PROTOCOL_VERSION_HEADER, envelopeResponseProtocolVersion(protocolVersion))
                .header(HttpHeaders.ALLOW, "POST")
                .build();
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
}
