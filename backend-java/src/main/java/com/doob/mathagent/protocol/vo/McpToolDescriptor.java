package com.doob.mathagent.protocol.vo;

import java.util.List;

/**
 * Read-only MCP tool descriptor returned to external clients.
 *
 * @param name stable MCP tool name
 * @param title human-readable tool title
 * @param description tool capability description without secrets or local paths
 * @param readOnly whether the described internal operation is read-only
 * @param executionEndpointEnabled whether this HTTP API exposes direct MCP execution
 * @param requiredRoles backend roles allowed to use the underlying capability
 * @param requiredScope logical scope external clients must request before execution is enabled
 * @param costLevel low/medium/high cost classification for future quota control
 * @param auditRequired whether execution must be written to audit logs
 * @param inputSchema JSON schema for future MCP tool arguments
 */
public record McpToolDescriptor(
        String name,
        String title,
        String description,
        boolean readOnly,
        boolean executionEndpointEnabled,
        List<String> requiredRoles,
        String requiredScope,
        String costLevel,
        boolean auditRequired,
        McpToolInputSchema inputSchema) {
}
