package com.doob.mathagent.teacher.document;

/**
 * Backend-controlled metadata for one visible physical FILE document.
 *
 * <p>The response intentionally contains relative source identity only. The shared ROOT remains an authorization and
 * synchronization scope; this record is the document identity used by block and vector retrieval.</p>
 */
public record TeacherFileDocumentResponse(
        String documentId,
        String rootDocumentId,
        String providerItemId,
        String sourcePath,
        String fileIdentityHash,
        String splitFingerprint,
        String title,
        String sourceType,
        String permissionScope,
        String syncStatus,
        String parseStatus,
        String embeddingStatus,
        String indexStatus) {

    public static TeacherFileDocumentResponse from(TeacherResourceStore.TeacherFileDocument file) {
        TeacherResourceDocumentResponse document = file.document();
        return new TeacherFileDocumentResponse(
                file.documentId(),
                file.rootDocumentId(),
                file.providerItemId(),
                file.sourcePath(),
                file.fileIdentityHash(),
                file.splitFingerprint(),
                document == null ? "" : document.title(),
                document == null ? "" : document.sourceType(),
                document == null ? "" : document.permissionScope(),
                document == null ? "" : document.syncStatus(),
                document == null ? "" : document.parseStatus(),
                document == null ? "" : document.embeddingStatus(),
                document == null ? "" : document.indexStatus());
    }
}
