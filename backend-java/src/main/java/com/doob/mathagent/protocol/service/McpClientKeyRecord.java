package com.doob.mathagent.protocol.service;

import java.time.LocalDateTime;

/**
 * Persisted MCP key metadata owned by one backend account.
 */
public record McpClientKeyRecord(
        String keyId,
        String tenantId,
        String ownerUserId,
        String ownerRole,
        String name,
        String secretHash,
        String secretPreview,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastUsedAt,
        LocalDateTime revokedAt) {
}
