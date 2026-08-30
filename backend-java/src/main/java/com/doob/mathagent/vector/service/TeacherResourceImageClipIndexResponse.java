package com.doob.mathagent.vector.service;

/** Result of one real teacher-page CLIP index rebuild. */
public record TeacherResourceImageClipIndexResponse(
        String documentId,
        int assetCount,
        int embeddedCount,
        int upsertedCount,
        String collectionName,
        int skippedCount,
        java.util.List<String> failedAssetIds) {

    public TeacherResourceImageClipIndexResponse {
        failedAssetIds = failedAssetIds == null ? java.util.List.of() : java.util.List.copyOf(failedAssetIds);
    }
}
