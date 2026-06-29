package com.doob.mathagent.protocol.vo;

import java.util.List;
import java.util.Map;

/**
 * MCP tool input schema exposed for discovery.
 *
 * @param type JSON schema root type, currently object
 * @param properties JSON schema field definitions for tool arguments
 * @param required required argument names
 */
public record McpToolInputSchema(
        String type,
        Map<String, Map<String, Object>> properties,
        List<String> required) {
}
