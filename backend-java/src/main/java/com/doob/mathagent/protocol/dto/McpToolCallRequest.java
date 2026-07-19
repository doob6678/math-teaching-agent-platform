package com.doob.mathagent.protocol.dto;

import java.util.Map;

/**
 * Request body for executing one registered MCP tool.
 *
 * @param arguments JSON object containing tool arguments; the service validates per tool before use
 */
public record McpToolCallRequest(Map<String, Object> arguments) {

    /**
     * Normalizes missing arguments to an immutable empty map.
     */
    public McpToolCallRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
