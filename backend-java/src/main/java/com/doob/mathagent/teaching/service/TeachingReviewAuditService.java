package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingReviewPolicy;
import com.doob.mathagent.teaching.vo.TeachingReviewAuditResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Creates the immutable hash-only audit row after a review transition has succeeded. */
@Service
public class TeachingReviewAuditService {
    private final TeachingReviewAuditStore store;
    private final Clock clock;

    @Autowired
    public TeachingReviewAuditService(TeachingReviewAuditStore store) {
        this(store, Clock.systemUTC());
    }

    public TeachingReviewAuditService(TeachingReviewAuditStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public TeachingReviewAuditResponse record(
            String taskId, TeachingRequestContext context, TeachingReviewPolicy policy, String decision, String reason,
            String commonDraftHash, String qualityStatus, String teacherVersionHash, String studentVersionHash,
            String lectureVersionHash, String publishedStatus) {
        TeachingRequestContext subject = context.normalize();
        TeachingReviewAuditResponse audit = new TeachingReviewAuditResponse(
                UUID.randomUUID().toString(), taskId, subject.tenantId(), subject.subjectType(), subject.subjectId(),
                policy.name(), normalizeCode(decision), blankToEmpty(reason), requiredHash(commonDraftHash),
                normalizeCode(qualityStatus), blankToEmpty(teacherVersionHash), blankToEmpty(studentVersionHash),
                blankToEmpty(lectureVersionHash), normalizeCode(publishedStatus), Instant.now(clock));
        return store.append(audit);
    }

    public List<TeachingReviewAuditResponse> list(String taskId) {
        return store.list(taskId);
    }

    private static String normalizeCode(String value) {
        return blankToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private static String requiredHash(String value) {
        String normalized = blankToEmpty(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Review audit requires a common draft hash");
        }
        return normalized;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
