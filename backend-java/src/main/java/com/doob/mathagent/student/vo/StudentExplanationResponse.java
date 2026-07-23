package com.doob.mathagent.student.vo;

import java.util.List;

/**
 * Student explanation response rendered by the frontend as compact learning cards.
 *
 * @param explanationId server-generated id for retry/audit correlation
 * @param conversationId durable conversation id for follow-up context and history recovery
 * @param conversationTitle short title shown in the AI 讲题 workspace and history sidebar
 * @param tenantId backend-resolved tenant id
 * @param studentId backend-resolved student id, or inspected student id for teacher/admin views
 * @param viewerRole backend-resolved viewer role
 * @param questionText normalized question text used by retrieval
 * @param imageStatus image handling status; never claims OCR unless a real OCR/vision path produced text
 * @param imageUnderstanding safe vision/OCR metadata when an uploaded image was analyzed
 * @param generatedBy orchestrator name and version
 * @param aiDraft model generation metadata for the card composition node
 * @param workflowStages DAG/ReAct-style stage states with timing and skip/failure details
 * @param cards frontend-ready explanation cards
 * @param sources source anchors shown beside cards and used for traceability
 * @param totalElapsedMs total backend orchestration time in milliseconds
 */
public record StudentExplanationResponse(
        String explanationId,
        String conversationId,
        String conversationTitle,
        String tenantId,
        String studentId,
        String viewerRole,
        String questionText,
        String imageStatus,
        ImageUnderstanding imageUnderstanding,
        String generatedBy,
        AiDraft aiDraft,
        List<WorkflowStage> workflowStages,
        List<ExplanationCard> cards,
        List<ExplanationSource> sources,
        long totalElapsedMs) {

    /**
     * Keeps older history fixtures and integrations source-compatible after conversation titles became durable data.
     *
     * <p>The legacy payload did not have a title field, so an empty title is the only honest value until the history
     * store derives one from the persisted conversation.</p>
     */
    public StudentExplanationResponse(
            String explanationId,
            String conversationId,
            String tenantId,
            String studentId,
            String viewerRole,
            String questionText,
            String imageStatus,
            ImageUnderstanding imageUnderstanding,
            String generatedBy,
            AiDraft aiDraft,
            List<WorkflowStage> workflowStages,
            List<ExplanationCard> cards,
            List<ExplanationSource> sources,
            long totalElapsedMs) {
        this(
                explanationId,
                conversationId,
                "",
                tenantId,
                studentId,
                viewerRole,
                questionText,
                imageStatus,
                imageUnderstanding,
                generatedBy,
                aiDraft,
                workflowStages,
                cards,
                sources,
                totalElapsedMs);
    }

    /**
     * One orchestration stage.
     *
     * @param stageKey stable stage key for frontend progress display
     * @param title short display title
     * @param status completed, skipped, or failed
     * @param detail concise stage detail or failure reason
     * @param elapsedMs stage elapsed time in milliseconds
     */
    public record WorkflowStage(
            String stageKey,
            String title,
            String status,
            String detail,
            long elapsedMs) {
    }

    /**
     * One explanation card inspired by student learning app card layouts.
     *
     * @param cardKey stable card key
     * @param title card title
     * @param summary short explanation body
     * @param items scannable bullet items
     * @param sourceUris source URIs supporting this card
     * @param renderMode frontend render hint such as text, formula, or source_list
     */
    public record ExplanationCard(
            String cardKey,
            String title,
            String summary,
            List<String> items,
            List<String> sourceUris,
            String renderMode) {
    }

    /**
     * Evidence source shown to the student or teacher.
     *
     * @param sourceType textbook, teacher_resource, or knowledge_graph
     * @param title source display title
     * @param sourceUri stable URI that can be copied into MCP/resource flows
     * @param permissionScope backend-controlled visibility scope
     * @param snippet compact evidence text
     * @param score retrieval or match score
     * @param sourcePath source page path, block path, or chapter path shown beside the link
     * @param openUrl clickable local/remote URL when the backend can expose one safely
     */
    public record ExplanationSource(
            String sourceType,
            String title,
            String sourceUri,
            String permissionScope,
            String snippet,
            double score,
            String sourcePath,
            String openUrl) {
    }

    /**
     * Safe metadata from the real vision/OCR stage.
     *
     * @param enabled whether a vision call was attempted
     * @param succeeded whether visible problem text was extracted
     * @param providerName provider used for image understanding
     * @param modelCode model used for image understanding
     * @param problemText extracted visible problem text
     * @param confidence model-reported confidence normalized to 0..1
     * @param promptTokens provider-reported prompt tokens
     * @param completionTokens provider-reported completion tokens
     * @param totalTokens provider-reported total tokens
     * @param message safe status message
     */
    public record ImageUnderstanding(
            boolean enabled,
            boolean succeeded,
            String providerName,
            String modelCode,
            String problemText,
            double confidence,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String message) {

        /**
         * Empty image understanding metadata for text-only requests.
         */
        public static ImageUnderstanding none() {
            return new ImageUnderstanding(false, false, "", "", "", 0.0, 0, 0, 0, "none");
        }
    }

    /**
     * Safe AI generation metadata shown by the frontend without exposing raw prompts or raw provider output.
     *
     * @param enabled whether a live model call was attempted
     * @param providerName provider that answered or was attempted
     * @param modelCode model that answered or was attempted
     * @param promptTokens provider-reported prompt tokens
     * @param completionTokens provider-reported completion tokens
     * @param totalTokens provider-reported total tokens
     * @param structured whether model output parsed into the expected card schema
     * @param message safe status message
     * @param recoveryEvents retry, parse, and provider-rotation events
     */
    public record AiDraft(
            boolean enabled,
            String providerName,
            String modelCode,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean structured,
            String message,
            List<AiRecoveryEvent> recoveryEvents) {

        /**
         * Returns a disabled AI node result.
         */
        public static AiDraft disabled(String message) {
            return new AiDraft(false, "", "", 0, 0, 0, false, message, List.of());
        }
    }

    /**
     * One safe AI recovery event.
     *
     * @param eventType event type such as MODEL_CALL_SUCCEEDED, JSON_PARSE_FAILED, or PROVIDER_ROTATED
     * @param providerName provider involved in this event
     * @param modelCode model involved in this event
     * @param attemptNo zero-based attempt number
     * @param structured whether this event produced structured card output
     * @param retryable whether backend still had retry or fallback capacity
     * @param message short safe message
     */
    public record AiRecoveryEvent(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean structured,
            boolean retryable,
            String message) {
    }
}
