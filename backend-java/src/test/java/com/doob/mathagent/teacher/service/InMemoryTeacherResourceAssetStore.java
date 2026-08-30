package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.asset.TeacherResourceAssetStore;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory asset store for focused sync tests.
 */
public class InMemoryTeacherResourceAssetStore implements TeacherResourceAssetStore {

    private final Map<String, TeacherResourceAssetResponse> assets = new ConcurrentHashMap<>();

    @Override
    public TeacherResourceAssetResponse save(TeacherResourceAssetResponse asset) {
        assets.put(asset.assetId(), asset);
        return asset;
    }

    @Override
    public Optional<TeacherResourceAssetResponse> find(String tenantId, String assetId) {
        TeacherResourceAssetResponse asset = assets.get(assetId);
        if (asset == null || !asset.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(asset);
    }

    @Override
    public Optional<TeacherResourceAssetResponse> findActiveByProviderChecksum(
            String tenantId,
            String documentId,
            String providerAssetId,
            String checksum) {
        return assets.values().stream()
                .filter(asset -> asset.tenantId().equals(tenantId))
                .filter(asset -> asset.documentId().equals(documentId))
                .filter(asset -> asset.providerAssetId().equals(providerAssetId))
                .filter(asset -> asset.checksum().equals(checksum))
                .filter(asset -> "active".equals(asset.status()))
                .findFirst();
    }

    @Override
    public Optional<TeacherResourceAssetResponse> findByProviderChecksum(
            String tenantId,
            String documentId,
            String providerAssetId,
            String checksum) {
        return assets.values().stream()
                .filter(asset -> asset.tenantId().equals(tenantId))
                .filter(asset -> asset.documentId().equals(documentId))
                .filter(asset -> asset.providerAssetId().equals(providerAssetId))
                .filter(asset -> asset.checksum().equals(checksum))
                .findFirst();
    }

    @Override
    public void markDocumentAssetsInactive(String tenantId, String documentId) {
        List<TeacherResourceAssetResponse> snapshot = new ArrayList<>(assets.values());
        for (TeacherResourceAssetResponse asset : snapshot) {
            if (!asset.tenantId().equals(tenantId) || !asset.documentId().equals(documentId)) {
                continue;
            }
            save(new TeacherResourceAssetResponse(
                    asset.assetId(),
                    asset.tenantId(),
                    asset.ownerSubjectId(),
                    asset.documentId(),
                    asset.blockId(),
                    asset.permissionScope(),
                    asset.sourcePath(),
                    asset.pageNo(),
                    asset.providerAssetId(),
                    asset.checksum(),
                    asset.mimeType(),
                    asset.width(),
                    asset.height(),
                    asset.storageKey(),
                    "inactive"));
        }
    }

    public List<TeacherResourceAssetResponse> snapshot() {
        return new ArrayList<>(assets.values());
    }

    @Override
    public List<TeacherResourceAssetResponse> listByDocument(String tenantId, String documentId) {
        return assets.values().stream()
                .filter(asset -> asset.tenantId().equals(tenantId) && asset.documentId().equals(documentId))
                .toList();
    }

    @Override
    public List<TeacherResourceAssetResponse> listByLogicalPath(String tenantId, String logicalPath) {
        return assets.values().stream()
                .filter(asset -> asset.tenantId().equals(tenantId) && asset.sourcePath().equals(logicalPath))
                .toList();
    }
}
