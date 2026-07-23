package com.doob.mathagent.vector.service;

/** Result of one real teacher-page CLIP index rebuild. */
public record TeacherResourceImageClipIndexResponse(
        String documentId,
        int assetCount,
        int embeddedCount,
        int upsertedCount,
        String collectionName) {
}
