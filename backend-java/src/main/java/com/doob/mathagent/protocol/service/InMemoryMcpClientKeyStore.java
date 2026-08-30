package com.doob.mathagent.protocol.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory fallback MCP key store for local runtime paths without MySQL.
 */
@Repository
public class InMemoryMcpClientKeyStore implements McpClientKeyStore {

    private final Map<String, McpClientKeyRecord> records = new ConcurrentHashMap<>();

    @Override
    public void create(McpClientKeyRecord record) {
        records.put(record.keyId(), record);
    }

    @Override
    public Optional<McpClientKeyRecord> findActiveBySecretHash(String secretHash) {
        return records.values().stream()
                .filter(record -> "active".equals(record.status()))
                .filter(record -> record.secretHash().equalsIgnoreCase(secretHash))
                .findFirst();
    }

    @Override
    public List<McpClientKeyRecord> listByOwner(String tenantId, String ownerUserId) {
        return records.values().stream()
                .filter(record -> tenantId.equals(record.tenantId()))
                .filter(record -> ownerUserId.equals(record.ownerUserId()))
                .sorted(Comparator.comparing(McpClientKeyRecord::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public Optional<McpClientKeyRecord> findByOwnerAndKeyId(String tenantId, String ownerUserId, String keyId) {
        McpClientKeyRecord record = records.get(keyId);
        if (record == null) {
            return Optional.empty();
        }
        if (!tenantId.equals(record.tenantId()) || !ownerUserId.equals(record.ownerUserId())) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void updateLastUsedAt(String keyId, LocalDateTime lastUsedAt) {
        records.computeIfPresent(keyId, (ignored, existing) -> new McpClientKeyRecord(
                existing.keyId(),
                existing.tenantId(),
                existing.ownerUserId(),
                existing.ownerRole(),
                existing.name(),
                existing.secretHash(),
                existing.secretPreview(),
                existing.status(),
                existing.createdAt(),
                lastUsedAt,
                lastUsedAt,
                existing.revokedAt()));
    }

    @Override
    public boolean revoke(String tenantId, String ownerUserId, String keyId, LocalDateTime revokedAt) {
        McpClientKeyRecord existing = records.get(keyId);
        if (existing == null) {
            return false;
        }
        if (!tenantId.equals(existing.tenantId()) || !ownerUserId.equals(existing.ownerUserId())) {
            return false;
        }
        if (!"active".equals(existing.status())) {
            return false;
        }
        records.put(keyId, new McpClientKeyRecord(
                existing.keyId(),
                existing.tenantId(),
                existing.ownerUserId(),
                existing.ownerRole(),
                existing.name(),
                existing.secretHash(),
                existing.secretPreview(),
                "revoked",
                existing.createdAt(),
                revokedAt,
                existing.lastUsedAt(),
                revokedAt));
        return true;
    }

    @Override
    public boolean deleteRevoked(String tenantId, String ownerUserId, String keyId) {
        McpClientKeyRecord existing = records.get(keyId);
        if (existing == null) {
            return false;
        }
        if (!tenantId.equals(existing.tenantId()) || !ownerUserId.equals(existing.ownerUserId())) {
            return false;
        }
        if (!"revoked".equals(existing.status())) {
            return false;
        }
        records.remove(keyId);
        return true;
    }
}
