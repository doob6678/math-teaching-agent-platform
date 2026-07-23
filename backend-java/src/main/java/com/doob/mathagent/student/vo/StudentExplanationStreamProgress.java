package com.doob.mathagent.student.vo;

import java.util.List;

/**
 * Real-time student explanation snapshot used by the streaming UI.
 *
 * @param conversationId durable conversation id used for follow-up messages
 * @param conversationTitle latest short title for the current explanation conversation
 * @param questionText normalized question text currently being explained
 * @param imageStatus current backend image handling state
 * @param imageUnderstanding latest safe image-understanding metadata
 * @param aiDraft latest safe AI metadata; disabled until the model stage starts
 * @param workflowStages current real workflow stages, including running/completed/failed states
 * @param cards explanation cards already available to the frontend
 * @param sources evidence sources already available to the frontend
 * @param totalElapsedMs current backend elapsed time
 */
public record StudentExplanationStreamProgress(
        String conversationId,
        String conversationTitle,
        String questionText,
        String imageStatus,
        StudentExplanationResponse.ImageUnderstanding imageUnderstanding,
        StudentExplanationResponse.AiDraft aiDraft,
        List<StudentExplanationResponse.WorkflowStage> workflowStages,
        List<StudentExplanationResponse.ExplanationCard> cards,
        List<StudentExplanationResponse.ExplanationSource> sources,
        long totalElapsedMs) {
}
