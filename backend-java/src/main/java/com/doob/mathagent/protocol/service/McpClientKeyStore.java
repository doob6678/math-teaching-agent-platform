package com.doob.mathagent.protocol.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for account-owned MCP keys.
 */
public interface McpClientKeyStore {

    /**
     * Saves one newly generated MCP key record.
     */
    void create(McpClientKeyRecord record);

    /**
     * Finds one active key by hashed secret.
     */
    Optional<McpClientKeyRecord> findActiveBySecretHash(String secretHash);

    /**
     * Lists keys owned by one backend account.
     */
    List<McpClientKeyRecord> listByOwner(String tenantId, String ownerUserId);

    /**
     * Finds one owned key by id regardless of current status.
     */
    Optional<McpClientKeyRecord> findByOwnerAndKeyId(String tenantId, String ownerUserId, String keyId);

    /**
     * Updates the most recent successful use timestamp.
     */
    void updateLastUsedAt(String keyId, LocalDateTime lastUsedAt);

    /**
     * Revokes one owned key and returns whether a row changed.
     */
    boolean revoke(String tenantId, String ownerUserId, String keyId, LocalDateTime revokedAt);

    /**
     * Physically deletes one owned key, but only when it is already revoked. Acceptance scripts and rotation
     * hygiene accumulate revoked rows forever otherwise; active keys must never be deletable this way.
     */
    boolean deleteRevoked(String tenantId, String ownerUserId, String keyId);
}
