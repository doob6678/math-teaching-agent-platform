package com.doob.mathagent.protocol.vo;

import java.time.LocalDateTime;

/**
 * One MCP key owned by the current authenticated backend user.
 */
public record McpClientKeyResponse(
        String keyId,
        String name,
        String tenantId,
        String ownerUserId,
        String keyProfile,
        String status,
        String secretKeyPreview,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        LocalDateTime revokedAt) {
}
