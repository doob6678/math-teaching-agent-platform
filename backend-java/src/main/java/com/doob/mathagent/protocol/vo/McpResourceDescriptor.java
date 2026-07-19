package com.doob.mathagent.protocol.vo;

import java.util.List;

/**
 * Standard MCP resource descriptor exposed through resources/list.
 *
 * @param uri stable resource URI accepted by resources/read
 * @param name stable resource name
 * @param title display title for MCP clients
 * @param description concise resource description
 * @param mimeType MIME type returned by resources/read
 * @param allowedProfiles backend profiles allowed to read this resource
 */
public record McpResourceDescriptor(
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        List<String> allowedProfiles) {
}
