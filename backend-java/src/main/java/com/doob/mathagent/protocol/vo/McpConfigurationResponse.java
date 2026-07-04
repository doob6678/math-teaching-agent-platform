package com.doob.mathagent.protocol.vo;

import java.util.List;

/**
 * Validated MCP configuration template response for frontend copy actions.
 *
 * @param serverName stable MCP server name used in client JSON
 * @param url externally reachable MCP base URL
 * @param valid whether the supplied URL and secret key passed configuration validation
 * @param secretKeyAccepted whether the submitted secret key met minimum policy
 * @param secretKeyPreview redacted preview of the submitted secret key
 * @param secretEnvName environment variable name referenced by the JSON template
 * @param keyProfile backend-derived key profile used to filter tools and prompts
 * @param exposedTools final MCP tool names exposed after backend profile filtering
 * @param exposedPrompts final MCP prompt names exposed after backend profile filtering
 * @param configJson copyable JSON template that never contains raw secret values
 * @param layers layered MCP usage explanation for frontend display
 */
public record McpConfigurationResponse(
        String serverName,
        String url,
        boolean valid,
        boolean secretKeyAccepted,
        String secretKeyPreview,
        String secretEnvName,
        String keyProfile,
        List<String> exposedTools,
        List<String> exposedPrompts,
        String configJson,
        List<Layer> layers) {

    /**
     * One layer in the MCP configuration and usage model.
     *
     * @param code stable layer code
     * @param name display name
     * @param description layer description
     * @param requiredCredential credential expected at this layer
     * @param allowedOperations operations allowed by this layer
     */
    public record Layer(
            String code,
            String name,
            String description,
            String requiredCredential,
            List<String> allowedOperations) {
    }
}
