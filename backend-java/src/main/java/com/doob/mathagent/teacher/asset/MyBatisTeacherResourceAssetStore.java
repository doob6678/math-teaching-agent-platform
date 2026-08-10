package com.doob.mathagent.teacher.asset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.teacher.entity.TeacherResourceAssetEntity;
import com.doob.mathagent.teacher.mapper.TeacherResourceAssetMapper;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import java.util.Optional;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed store for extracted images/assets.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherResourceAssetStore implements TeacherResourceAssetStore {

    private final TeacherResourceAssetMapper mapper;

    public MyBatisTeacherResourceAssetStore(TeacherResourceAssetMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TeacherResourceAssetResponse save(TeacherResourceAssetResponse asset) {
        TeacherResourceAssetEntity entity = toEntity(asset);
        TeacherResourceAssetEntity existing = mapper.selectById(asset.assetId());
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return toResponse(entity);
    }

    @Override
    public Optional<TeacherResourceAssetResponse> find(String tenantId, String assetId) {
        TeacherResourceAssetEntity entity = mapper.selectById(assetId);
        if (entity == null || !text(tenantId).equals(entity.getTenantId())) {
            return Optional.empty();
        }
        return Optional.of(toResponse(entity));
    }

    @Override
    public Optional<TeacherResourceAssetResponse> findActiveByProviderChecksum(
            String tenantId,
            String documentId,
            String providerAssetId,
            String checksum) {
        Long numericDocumentId = parseId(documentId);
        if (numericDocumentId == null) {
            return Optional.empty();
        }
        return mapper.selectPage(Page.of(1, 1), new LambdaQueryWrapper<TeacherResourceAssetEntity>()
                        .eq(TeacherResourceAssetEntity::getTenantId, tenantId)
                        .eq(TeacherResourceAssetEntity::getDocumentId, numericDocumentId)
                        .eq(TeacherResourceAssetEntity::getProviderAssetId, providerAssetId)
                        .eq(TeacherResourceAssetEntity::getChecksum, checksum)
                        .eq(TeacherResourceAssetEntity::getStatus, "active"))
                .getRecords()
                .stream()
                .findFirst()
                .map(MyBatisTeacherResourceAssetStore::toResponse);
    }

    @Override
    public Optional<TeacherResourceAssetResponse> findByProviderChecksum(
            String tenantId,
            String documentId,
            String providerAssetId,
            String checksum) {
        Long numericDocumentId = parseId(documentId);
        if (numericDocumentId == null) {
            return Optional.empty();
        }
        return mapper.selectPage(Page.of(1, 1), new LambdaQueryWrapper<TeacherResourceAssetEntity>()
                        .eq(TeacherResourceAssetEntity::getTenantId, tenantId)
                        .eq(TeacherResourceAssetEntity::getDocumentId, numericDocumentId)
                        .eq(TeacherResourceAssetEntity::getProviderAssetId, providerAssetId)
                        .eq(TeacherResourceAssetEntity::getChecksum, checksum)
                        .orderByDesc(TeacherResourceAssetEntity::getUpdatedAt))
                .getRecords()
                .stream()
                .findFirst()
                .map(MyBatisTeacherResourceAssetStore::toResponse);
    }

    @Override
    public void markDocumentAssetsInactive(String tenantId, String documentId) {
        Long numericDocumentId = parseId(documentId);
        if (numericDocumentId == null) {
            return;
        }
        mapper.update(
                null,
                new LambdaUpdateWrapper<TeacherResourceAssetEntity>()
                        .eq(TeacherResourceAssetEntity::getTenantId, tenantId)
                        .eq(TeacherResourceAssetEntity::getDocumentId, numericDocumentId)
                        .eq(TeacherResourceAssetEntity::getStatus, "active")
                        .set(TeacherResourceAssetEntity::getStatus, "inactive"));
    }

    @Override
    public List<TeacherResourceAssetResponse> listByDocument(String tenantId, String documentId) {
        Long numericDocumentId = parseId(documentId);
        if (numericDocumentId == null) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<TeacherResourceAssetEntity>()
                        .eq(TeacherResourceAssetEntity::getTenantId, tenantId)
                        .eq(TeacherResourceAssetEntity::getDocumentId, numericDocumentId))
                .stream().map(MyBatisTeacherResourceAssetStore::toResponse).toList();
    }

    @Override
    public void purgeDocumentAssets(String tenantId, String documentId) {
        Long numericDocumentId = parseId(documentId);
        if (numericDocumentId == null) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<TeacherResourceAssetEntity>()
                .eq(TeacherResourceAssetEntity::getTenantId, tenantId)
                .eq(TeacherResourceAssetEntity::getDocumentId, numericDocumentId));
    }

    private static TeacherResourceAssetEntity toEntity(TeacherResourceAssetResponse asset) {
        TeacherResourceAssetEntity entity = new TeacherResourceAssetEntity();
        entity.setAssetId(asset.assetId());
        entity.setTenantId(asset.tenantId());
        entity.setOwnerSubjectId(asset.ownerSubjectId());
        entity.setDocumentId(parseId(asset.documentId()));
        entity.setBlockId(parseId(asset.blockId()));
        entity.setPermissionScope(asset.permissionScope());
        entity.setSourcePath(asset.sourcePath());
        entity.setPageNo(asset.pageNo());
        entity.setProviderAssetId(asset.providerAssetId());
        entity.setChecksum(asset.checksum());
        entity.setMimeType(asset.mimeType());
        entity.setWidth(asset.width());
        entity.setHeight(asset.height());
        entity.setStorageKey(asset.storageKey());
        entity.setStatus(asset.status());
        return entity;
    }

    private static TeacherResourceAssetResponse toResponse(TeacherResourceAssetEntity entity) {
        return new TeacherResourceAssetResponse(
                entity.getAssetId(),
                entity.getTenantId(),
                entity.getOwnerSubjectId(),
                entity.getDocumentId() == null ? "" : String.valueOf(entity.getDocumentId()),
                entity.getBlockId() == null ? null : String.valueOf(entity.getBlockId()),
                entity.getPermissionScope(),
                entity.getSourcePath(),
                entity.getPageNo(),
                entity.getProviderAssetId(),
                entity.getChecksum(),
                entity.getMimeType(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getStorageKey(),
                entity.getStatus());
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
