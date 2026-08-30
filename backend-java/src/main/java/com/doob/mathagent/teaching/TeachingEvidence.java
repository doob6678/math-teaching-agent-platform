package com.doob.mathagent.teaching;

import java.util.List;
import java.util.Map;

/**
 * 教学任务使用的证据。
 *
 * <p>Canonical 题号仅作为 Java 内部的 manifest 选择器持久化。它不是路径、文件名或模型可见文档
 * 标识，broker 只能从当前 run 已授权的证据行导出对应题目。</p>
 */
public record TeachingEvidence(
        String sourceScope,
        String sourceTitle,
        String chunkId,
        int pageNo,
        String snippet,
        String imagePath,
        String imageDescription,
        String sourceDocumentId,
        String sourceType,
        String sourceUrl,
        String sourcePath,
        List<String> assetIds,
        String canonicalQuestionNumber,
        List<Map<String, String>> imageRefs) {

    public TeachingEvidence {
        sourceScope = sourceScope == null ? "" : sourceScope;
        sourceTitle = sourceTitle == null ? "" : sourceTitle;
        chunkId = chunkId == null ? "" : chunkId;
        snippet = snippet == null ? "" : snippet;
        imagePath = imagePath == null ? "" : imagePath;
        imageDescription = imageDescription == null ? "" : imageDescription;
        sourceDocumentId = sourceDocumentId == null ? "" : sourceDocumentId;
        sourceType = sourceType == null ? "" : sourceType;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        sourcePath = sourcePath == null ? "" : sourcePath;
        assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        canonicalQuestionNumber = canonicalQuestionNumber == null ? "" : canonicalQuestionNumber;
        imageRefs = imageRefs == null ? List.of() : imageRefs.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(item -> Map.of(
                        "markdownLine", item.getOrDefault("markdownLine", ""),
                        "logicalPath", item.getOrDefault("logicalPath", "")))
                .filter(item -> !item.get("markdownLine").isBlank() && !item.get("logicalPath").isBlank())
                .limit(12)
                .toList();
    }

    /** Keeps the prior full evidence contract source-compatible. */
    public TeachingEvidence(
            String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet, String imagePath,
            String imageDescription, String sourceDocumentId, String sourceType, String sourceUrl, String sourcePath,
            List<String> assetIds, String canonicalQuestionNumber) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, imageDescription, sourceDocumentId,
                sourceType, sourceUrl, sourcePath, assetIds, canonicalQuestionNumber, List.of());
    }

    /** Keeps the prior full evidence contract source-compatible. */
    public TeachingEvidence(
            String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet, String imagePath,
            String imageDescription, String sourceDocumentId, String sourceType, String sourceUrl, String sourcePath,
            List<String> assetIds) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, imageDescription, sourceDocumentId,
                sourceType, sourceUrl, sourcePath, assetIds, "", List.of());
    }

    /** Keeps the original eight-field retrieval contract source-compatible. */
    public TeachingEvidence(
            String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet, String imagePath,
            String imageDescription, String sourceDocumentId) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, imageDescription, sourceDocumentId,
                "", "", "", List.of(), "", List.of());
    }

    /** Preserves current renderers that do not have a teacher-resource inspection reference. */
    public TeachingEvidence(
            String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet, String imagePath,
            String imageDescription) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, imageDescription, "", "", "", "", List.of(), "", List.of());
    }

    /** Preserves existing callers that have an image but no verified visual description yet. */
    public TeachingEvidence(
            String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet, String imagePath) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, "", "", "", "", "", List.of(), "", List.of());
    }

    /** Preserves existing retrieval callers that have text-only evidence. */
    public TeachingEvidence(String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, "", "", "", "", "", "", List.of(), "", List.of());
    }
}
