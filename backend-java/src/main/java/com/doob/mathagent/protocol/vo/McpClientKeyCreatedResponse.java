package com.doob.mathagent.protocol.vo;

/**
 * Response returned once immediately after backend key generation.
 */
public record McpClientKeyCreatedResponse(
        String keyId,
        String name,
        String tenantId,
        String ownerUserId,
        String keyProfile,
        String secretKey,
        String secretKeyPreview,
        McpConfigurationResponse configuration) {
}
