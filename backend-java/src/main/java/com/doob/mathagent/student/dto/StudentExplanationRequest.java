package com.doob.mathagent.student.dto;

/**
 * Request body for student-side question explanation.
 *
 * @param conversationId optional backend-issued conversation id for follow-up context
 * @param questionText question text typed by the student or extracted by a real OCR/vision step
 * @param imageUploadId optional backend-issued temporary image upload id
 * @param imageFileName optional uploaded image file name; metadata is recorded but not treated as OCR text
 * @param imageContentType optional uploaded image MIME type
 * @param imageSizeBytes optional uploaded image size in bytes
 * @param searchTextbook whether the backend may search public textbook RAG resources
 * @param searchKnowledgeGraph whether the backend may match the curated display knowledge graph
 * @param searchTeacherResources whether the backend may search teacher resource blocks; only teacher/admin subjects can use it
 * @param maxTextbookHits maximum textbook evidence hits to use
 * @param maxTeacherResourceHits maximum teacher resource hits to use
 * @param useConversationMemory legacy client flag; an existing conversationId always loads its own history
 * @param preferredProviderName optional model switch selected in the chat UI; backend validates against the catalog
 * @param preferredModelCode optional model code selected in the chat UI; blank falls back to the default route
 */
public record StudentExplanationRequest(
        String conversationId,
        String questionText,
        String imageUploadId,
        String imageFileName,
        String imageContentType,
        Long imageSizeBytes,
        Boolean searchTextbook,
        Boolean searchKnowledgeGraph,
        Boolean searchTeacherResources,
        Integer maxTextbookHits,
        Integer maxTeacherResourceHits,
        Boolean useConversationMemory,
        String clientRequestId,
        String preferredProviderName,
        String preferredModelCode) {

    /** Preserves existing callers; the legacy memory flag is retained only for wire compatibility. */
    public StudentExplanationRequest(
            String conversationId,
            String questionText,
            String imageUploadId,
            String imageFileName,
            String imageContentType,
            Long imageSizeBytes,
            Boolean searchTextbook,
            Boolean searchKnowledgeGraph,
            Boolean searchTeacherResources,
            Integer maxTextbookHits,
            Integer maxTeacherResourceHits) {
        this(
                conversationId,
                questionText,
                imageUploadId,
                imageFileName,
                imageContentType,
                imageSizeBytes,
                searchTextbook,
                searchKnowledgeGraph,
                searchTeacherResources,
                maxTextbookHits,
                maxTeacherResourceHits,
                false,
                null,
                null,
                null);
    }

    /** 保留已存在的 JSON 调用方，同时允许显式传入旧版 memory 开关。 */
    public StudentExplanationRequest(
            String conversationId,
            String questionText,
            String imageUploadId,
            String imageFileName,
            String imageContentType,
            Long imageSizeBytes,
            Boolean searchTextbook,
            Boolean searchKnowledgeGraph,
            Boolean searchTeacherResources,
            Integer maxTextbookHits,
            Integer maxTeacherResourceHits,
            Boolean useConversationMemory) {
        this(
                conversationId,
                questionText,
                imageUploadId,
                imageFileName,
                imageContentType,
                imageSizeBytes,
                searchTextbook,
                searchKnowledgeGraph,
                searchTeacherResources,
                maxTextbookHits,
                maxTeacherResourceHits,
                useConversationMemory,
                null,
                null,
                null);
    }

    /**
     * Returns a normalized request with bounded retrieval limits and safe default toggles.
     * Conversation history is decided by conversationId in the service, not by the legacy flag.
     */
    public StudentExplanationRequest normalize() {
        return new StudentExplanationRequest(
                textOrNull(conversationId),
                textOrNull(questionText),
                textOrNull(imageUploadId),
                textOrNull(imageFileName),
                textOrNull(imageContentType),
                imageSizeBytes == null || imageSizeBytes < 0 ? null : imageSizeBytes,
                searchTextbook == null || searchTextbook,
                searchKnowledgeGraph == null || searchKnowledgeGraph,
                searchTeacherResources != null && searchTeacherResources,
                clamp(maxTextbookHits, 3, 1, 8),
                clamp(maxTeacherResourceHits, 3, 1, 6),
                Boolean.TRUE.equals(useConversationMemory),
                normalizeClientRequestId(clientRequestId),
                // 模型偏好是可选路由提示，限长防注入；空串归一为 null 让 providerRoute 走默认路由。
                boundedOrNull(preferredProviderName, 40),
                boundedOrNull(preferredModelCode, 80));
    }

    /**
     * Returns whether the request contains any user-supplied problem input.
     */
    public boolean hasProblemInput() {
        StudentExplanationRequest normalized = normalize();
        return normalized.questionText() != null
                || normalized.imageUploadId() != null
                || normalized.imageFileName() != null;
    }

    /**
     * Returns a copy bound to a backend-generated or caller-supplied conversation id.
     */
    public StudentExplanationRequest withConversationId(String nextConversationId) {
        return new StudentExplanationRequest(
                textOrNull(nextConversationId),
                questionText,
                imageUploadId,
                imageFileName,
                imageContentType,
                imageSizeBytes,
                searchTextbook,
                searchKnowledgeGraph,
                searchTeacherResources,
                maxTextbookHits,
                maxTeacherResourceHits,
                useConversationMemory,
                clientRequestId,
                preferredProviderName,
                preferredModelCode).normalize();
    }

    /** 返回携带稳定请求幂等键的副本，供同一轮工作流恢复使用。 */
    public StudentExplanationRequest withClientRequestId(String nextClientRequestId) {
        return new StudentExplanationRequest(
                conversationId,
                questionText,
                imageUploadId,
                imageFileName,
                imageContentType,
                imageSizeBytes,
                searchTextbook,
                searchKnowledgeGraph,
                searchTeacherResources,
                maxTextbookHits,
                maxTeacherResourceHits,
                useConversationMemory,
                nextClientRequestId,
                preferredProviderName,
                preferredModelCode).normalize();
    }

    /** 将客户端幂等键限制为可审计的短文本；缺失时由服务端生成。 */
    private static String normalizeClientRequestId(String value) {
        String normalized = textOrNull(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 128) {
            return normalized.substring(0, 128);
        }
        return normalized;
    }

    /**
     * Clamps an optional integer into a safe inclusive range.
     */
    private static int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Converts blank text to null after stripping user input.
     */
    private static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 偏好字段限长截断；空白归一为 null，避免超长输入进入路由签名。 */
    private static String boundedOrNull(String value, int limit) {
        String normalized = textOrNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() > limit ? normalized.substring(0, limit) : normalized;
    }
}
