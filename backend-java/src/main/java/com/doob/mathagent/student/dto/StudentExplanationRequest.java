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
 * @param useConversationMemory whether the model may read previous turns from this conversation
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
        Boolean useConversationMemory) {

    /**
     * Preserves existing callers while making conversation memory explicit opt-in rather than an implicit default.
     */
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
                false);
    }

    /**
     * Returns a normalized request with bounded retrieval limits and safe default toggles.
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
                Boolean.TRUE.equals(useConversationMemory));
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
                useConversationMemory).normalize();
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
}
