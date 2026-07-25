package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingReviewAuditResponse;
import java.util.List;

/** Storage boundary for immutable review decisions, so production and tests share one service contract. */
public interface TeachingReviewAuditStore {
    TeachingReviewAuditResponse append(TeachingReviewAuditResponse audit);

    List<TeachingReviewAuditResponse> list(String taskId);
}
