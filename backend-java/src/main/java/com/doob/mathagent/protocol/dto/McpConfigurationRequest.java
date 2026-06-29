package com.doob.mathagent.protocol.dto;

import java.util.List;

/**
 * Request for validating and building a copyable MCP client configuration.
 *
 * @param url externally reachable MCP base URL provided by the user
 * @param secretKey secret value entered for validation only; it is never echoed in responses
 * @param secretEnvName environment variable name that the generated JSON should reference
 * @param enabledToolNames user-selected MCP tools; empty means request all tools allowed by the backend key profile
 * @param enabledPromptNames user-selected MCP prompts; empty means request all prompts allowed by the backend key profile
 */
public record McpConfigurationRequest(
        String url,
        String secretKey,
        String secretEnvName,
        List<String> enabledToolNames,
        List<String> enabledPromptNames) {
}
