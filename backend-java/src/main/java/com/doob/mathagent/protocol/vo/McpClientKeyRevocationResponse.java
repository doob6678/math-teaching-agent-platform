package com.doob.mathagent.protocol.vo;

import java.time.LocalDateTime;

/**
 * Revocation result for one owned MCP key.
 */
public record McpClientKeyRevocationResponse(
        String keyId,
        String status,
        LocalDateTime revokedAt) {
}
