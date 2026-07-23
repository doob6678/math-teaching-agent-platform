package com.doob.mathagent.vector.service;

/** Permission-filtered teacher page image CLIP hit with preserved PDF source metadata. */
public record TeacherResourceImageClipHit(
        double score,
        String documentId,
        String assetId,
        String title,
        String sourcePath,
        int pageNo,
        String permissionScope) {
}
