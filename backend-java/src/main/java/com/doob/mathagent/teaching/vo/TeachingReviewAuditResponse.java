package com.doob.mathagent.teaching.vo;

import java.time.Instant;

/**
 * Immutable, hash-only audit record for a publication decision.  Draft and handout bodies stay in their controlled
 * task storage; this record proves exactly which snapshot a human approved without duplicating private content.
 */
public record TeachingReviewAuditResponse(
        String reviewAuditId,
        String taskId,
        String tenantId,
        String reviewerSubjectType,
        String reviewerSubjectId,
        String policyCode,
        String decisionCode,
        String reasonText,
        String commonDraftHash,
        String qualityStatus,
        String teacherVersionHash,
        String studentVersionHash,
        String lectureVersionHash,
        String publishedStatus,
        Instant createdAt) {
}
