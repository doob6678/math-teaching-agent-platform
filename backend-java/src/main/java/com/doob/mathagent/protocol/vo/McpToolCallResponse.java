package com.doob.mathagent.protocol.vo;

/**
 * Response returned after a real MCP tool execution.
 *
 * @param toolName executed tool name
 * @param clientId registered MCP client id
 * @param tenantId backend tenant resolved from the MCP registry
 * @param subjectType backend subject type resolved from the MCP registry
 * @param subjectId backend subject id resolved from the MCP registry
 * @param result structured tool result
 */
public record McpToolCallResponse(
        String toolName,
        String clientId,
        String tenantId,
        String subjectType,
        String subjectId,
        Object result) {
}
