package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import java.util.Optional;

/**
 * Store abstraction for extracted resource assets.
 */
public interface TeacherResourceAssetStore {

    TeacherResourceAssetResponse save(TeacherResourceAssetResponse asset);

    Optional<TeacherResourceAssetResponse> find(String tenantId, String assetId);

    Optional<TeacherResourceAssetResponse> findActiveByProviderChecksum(
            String tenantId,
            String documentId,
            String providerAssetId,
            String checksum);

    void markDocumentAssetsInactive(String tenantId, String documentId);
}
