package com.doob.mathagent.protocol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.protocol.entity.McpClientKeyEntity;
import com.doob.mathagent.protocol.mapper.McpClientKeyMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed MCP key store for deployable account-owned keys.
 */
@Repository
@Primary
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisMcpClientKeyStore implements McpClientKeyStore {

    private final McpClientKeyMapper mapper;

    public MyBatisMcpClientKeyStore(McpClientKeyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(McpClientKeyRecord record) {
        mapper.insert(toEntity(record));
    }

    @Override
    public Optional<McpClientKeyRecord> findActiveBySecretHash(String secretHash) {
        return mapper.selectList(new LambdaQueryWrapper<McpClientKeyEntity>()
                        .eq(McpClientKeyEntity::getSecretHash, secretHash)
                        .eq(McpClientKeyEntity::getStatus, "active"))
                .stream()
                .findFirst()
                .map(MyBatisMcpClientKeyStore::toRecord);
    }

    @Override
    public List<McpClientKeyRecord> listByOwner(String tenantId, String ownerUserId) {
        return mapper.selectList(new LambdaQueryWrapper<McpClientKeyEntity>()
                        .eq(McpClientKeyEntity::getTenantId, tenantId)
                        .eq(McpClientKeyEntity::getOwnerUserId, ownerUserId)
                        .orderByDesc(McpClientKeyEntity::getCreatedAt))
                .stream()
                .map(MyBatisMcpClientKeyStore::toRecord)
                .toList();
    }

    @Override
    public Optional<McpClientKeyRecord> findByOwnerAndKeyId(String tenantId, String ownerUserId, String keyId) {
        return mapper.selectList(new LambdaQueryWrapper<McpClientKeyEntity>()
                        .eq(McpClientKeyEntity::getTenantId, tenantId)
                        .eq(McpClientKeyEntity::getOwnerUserId, ownerUserId)
                        .eq(McpClientKeyEntity::getKeyId, keyId))
                .stream()
                .findFirst()
                .map(MyBatisMcpClientKeyStore::toRecord);
    }

    @Override
    public void updateLastUsedAt(String keyId, LocalDateTime lastUsedAt) {
        mapper.update(
                null,
                new LambdaUpdateWrapper<McpClientKeyEntity>()
                        .eq(McpClientKeyEntity::getKeyId, keyId)
                        .set(McpClientKeyEntity::getLastUsedAt, lastUsedAt));
    }

    @Override
    public boolean revoke(String tenantId, String ownerUserId, String keyId, LocalDateTime revokedAt) {
        return mapper.update(
                        null,
                        new LambdaUpdateWrapper<McpClientKeyEntity>()
                                .eq(McpClientKeyEntity::getTenantId, tenantId)
                                .eq(McpClientKeyEntity::getOwnerUserId, ownerUserId)
                                .eq(McpClientKeyEntity::getKeyId, keyId)
                                .eq(McpClientKeyEntity::getStatus, "active")
                                .set(McpClientKeyEntity::getStatus, "revoked")
                                .set(McpClientKeyEntity::getRevokedAt, revokedAt))
                > 0;
    }

    private static McpClientKeyEntity toEntity(McpClientKeyRecord record) {
        McpClientKeyEntity entity = new McpClientKeyEntity();
        entity.setKeyId(record.keyId());
        entity.setTenantId(record.tenantId());
        entity.setOwnerUserId(record.ownerUserId());
        entity.setOwnerRole(record.ownerRole());
        entity.setName(record.name());
        entity.setSecretHash(record.secretHash());
        entity.setSecretPreview(record.secretPreview());
        entity.setStatus(record.status());
        entity.setCreatedAt(record.createdAt());
        entity.setUpdatedAt(record.updatedAt());
        entity.setLastUsedAt(record.lastUsedAt());
        entity.setRevokedAt(record.revokedAt());
        return entity;
    }

    private static McpClientKeyRecord toRecord(McpClientKeyEntity entity) {
        return new McpClientKeyRecord(
                entity.getKeyId(),
                entity.getTenantId(),
                entity.getOwnerUserId(),
                entity.getOwnerRole(),
                entity.getName(),
                entity.getSecretHash(),
                entity.getSecretPreview(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastUsedAt(),
                entity.getRevokedAt());
    }
}
