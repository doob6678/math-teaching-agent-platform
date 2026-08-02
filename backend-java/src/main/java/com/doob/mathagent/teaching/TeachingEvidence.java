package com.doob.mathagent.teaching;

import java.util.List;

/**
 * 教学任务使用的证据。
 *
 * @param sourceScope 证据作用域，例如 PUBLIC_TEXTBOOK、PRIVATE_FEISHU、USER_HISTORY。
 * @param sourceTitle 证据来源标题。
 * @param chunkId 教材或文档 chunk ID。
 * @param pageNo 教材 PDF 页码；非教材来源可为 0。
 * @param snippet 证据文本片段。
 * @param imagePath 已授权的本地教材页图路径；空值表示当前证据没有可嵌入图片。
 * @param imageDescription 已核验的图像可见信息；只用于模型理解，不包含本地路径或访问令牌。
 * @param sourceDocumentId opaque teacher-resource document id; used only by the authenticated inspection endpoint.
 * @param sourceType normalized source library, such as feishu, public_textbook, or question_bank.
 * @param sourceUrl permission-checked original source URL when the source provides one.
 * @param sourcePath human-readable path inside the source document or textbook corpus.
 * @param assetIds permission-checked opaque image asset ids associated with this evidence block.
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
        List<String> assetIds) {

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
    }

    /** Keeps the original eight-field retrieval contract source-compatible. */
    public TeachingEvidence(
            String sourceScope,
            String sourceTitle,
            String chunkId,
            int pageNo,
            String snippet,
            String imagePath,
            String imageDescription,
            String sourceDocumentId) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, imageDescription, sourceDocumentId,
                "", "", "", List.of());
    }

    /** Preserves current renderers that do not have a teacher-resource inspection reference. */
    public TeachingEvidence(
            String sourceScope,
            String sourceTitle,
            String chunkId,
            int pageNo,
            String snippet,
            String imagePath,
            String imageDescription) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, imageDescription, "", "", "", "", List.of());
    }

    /** Preserves existing callers that have an image but no verified visual description yet. */
    public TeachingEvidence(
            String sourceScope,
            String sourceTitle,
            String chunkId,
            int pageNo,
            String snippet,
            String imagePath) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, imagePath, "", "", "", "", "", List.of());
    }

    /** Preserves existing retrieval callers that have text-only evidence. */
    public TeachingEvidence(String sourceScope, String sourceTitle, String chunkId, int pageNo, String snippet) {
        this(sourceScope, sourceTitle, chunkId, pageNo, snippet, "", "", "", "", "", "", List.of());
    }
}
