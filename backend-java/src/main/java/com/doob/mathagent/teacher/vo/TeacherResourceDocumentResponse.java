package com.doob.mathagent.teacher.vo;

import java.util.List;

/**
 * Teacher resource document response used by the resource management page.
 *
 * @param documentId stable resource document id
 * @param tenantId tenant id that owns this resource
 * @param ownerSubjectId teacher/admin subject id that registered the resource
 * @param sourceType source type, such as feishu or local_path
 * @param title display title
 * @param originalUrl original remote URL, if any
 * @param localPath configured local file system path, if any
 * @param permissionScope resource access scope used by RAG permission checks
 * @param syncStatus sync status, such as registered or archived
 * @param parseStatus parse status for downstream file parsing tasks
 * @param embeddingStatus embedding status for downstream vector indexing tasks
 * @param indexStatus BM25/Milvus index rebuild status
 * @param feishuExportFormat native Feishu export format for Feishu sources; supported values are md, docx, and pdf
 * @param previewFiles small local file preview list shown in teacher UI
 * @param parseMode TEXT deterministic extraction or AI semantic labeling mode
 */
public record TeacherResourceDocumentResponse(
        String documentId,
        String tenantId,
        String ownerSubjectId,
        String sourceType,
        String title,
        String originalUrl,
        String localPath,
        String permissionScope,
        String syncStatus,
        String parseStatus,
        String embeddingStatus,
        String indexStatus,
        String feishuExportFormat,
        List<PreviewFile> previewFiles,
        String parseMode) {

    public TeacherResourceDocumentResponse(
            String documentId,
            String tenantId,
            String ownerSubjectId,
            String sourceType,
            String title,
            String originalUrl,
            String localPath,
            String permissionScope,
            String syncStatus,
            String parseStatus,
            String embeddingStatus,
            String indexStatus,
            String feishuExportFormat,
            List<PreviewFile> previewFiles) {
        this(
                documentId,
                tenantId,
                ownerSubjectId,
                sourceType,
                title,
                originalUrl,
                localPath,
                permissionScope,
                syncStatus,
                parseStatus,
                embeddingStatus,
                indexStatus,
                feishuExportFormat,
                previewFiles,
                "TEXT");
    }

    /**
     * Backward-compatible constructor for non-Feishu resources and older focused tests.
     */
    public TeacherResourceDocumentResponse(
            String documentId,
            String tenantId,
            String ownerSubjectId,
            String sourceType,
            String title,
            String originalUrl,
            String localPath,
            String permissionScope,
            String syncStatus,
            String parseStatus,
            String embeddingStatus,
            String indexStatus,
            List<PreviewFile> previewFiles) {
        this(
                documentId,
                tenantId,
                ownerSubjectId,
                sourceType,
                title,
                originalUrl,
                localPath,
                permissionScope,
                syncStatus,
                parseStatus,
                embeddingStatus,
                indexStatus,
                "feishu".equalsIgnoreCase(sourceType == null ? "" : sourceType) ? "md" : null,
                previewFiles,
                "TEXT");
    }

    /**
     * Small local file preview entry.
     *
     * @param fileName file name shown in the teacher resource preview
     * @param relativePath path relative to registered root when possible
     * @param fileSizeBytes file size in bytes
     */
    public record PreviewFile(String fileName, String relativePath, long fileSizeBytes) {
    }
}
