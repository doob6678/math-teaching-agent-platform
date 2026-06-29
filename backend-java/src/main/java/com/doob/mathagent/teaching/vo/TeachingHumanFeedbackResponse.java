package com.doob.mathagent.teaching.vo;

import java.time.Instant;

/**
 * Human feedback record returned for an owned teaching task.
 *
 * @param feedbackId backend-generated feedback id
 * @param taskId teaching task id that received the feedback
 * @param tenantId backend tenant id resolved from the session
 * @param subjectType backend subject role that submitted feedback
 * @param subjectId backend subject id that submitted feedback
 * @param rating numeric feedback score from 1 to 5
 * @param decision compact human review decision code
 * @param comment free-text feedback content
 * @param createdAt feedback creation time
 */
public record TeachingHumanFeedbackResponse(
        String feedbackId,
        String taskId,
        String tenantId,
        String subjectType,
        String subjectId,
        int rating,
        String decision,
        String comment,
        Instant createdAt) {
}
