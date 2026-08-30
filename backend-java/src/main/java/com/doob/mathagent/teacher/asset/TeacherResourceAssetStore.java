package com.doob.mathagent.teacher.asset;

import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import java.util.Optional;
import java.util.List;

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

    /**
     * Finds any generation (active or inactive) for the same provider asset/checksum.  A parser can persist an image
     * before Feishu manifest ingestion marks the previous generation inactive; reactivating that exact row keeps
     * block.imageRefs stable and avoids duplicate binaries during a single-document resync.
     */
    default Optional<TeacherResourceAssetResponse> findByProviderChecksum(
            String tenantId,
            String documentId,
            String providerAssetId,
            String checksum) {
        return listByDocument(tenantId, documentId).stream()
                .filter(asset -> providerAssetId.equals(asset.providerAssetId()))
                .filter(asset -> checksum.equals(asset.checksum()))
                .findFirst();
    }

    void markDocumentAssetsInactive(String tenantId, String documentId);

    /** Returns every asset row for a document, including inactive generations, before physical cleanup. */
    default List<TeacherResourceAssetResponse> listByDocument(String tenantId, String documentId) {
        return List.of();
    }

    /**
     * Finds asset rows by the exact persisted logical source path.
     *
     * <p>This lookup is used only after a run has already authorized the matching Markdown row. It intentionally does
     * not make a FILE document discoverable as a searchable resource.</p>
     */
    default List<TeacherResourceAssetResponse> listByLogicalPath(String tenantId, String logicalPath) {
        return List.of();
    }

    /** Removes the metadata rows only after the service has securely removed backend-owned binary files. */
    default void purgeDocumentAssets(String tenantId, String documentId) {
    }
}
