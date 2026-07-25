package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingReviewAuditResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

/** Test/local fallback that preserves append-only semantics without pretending to be durable storage. */
@Repository
@ConditionalOnMissingBean(TeachingReviewAuditStore.class)
public class InMemoryTeachingReviewAuditStore implements TeachingReviewAuditStore {
    private final Map<String, List<TeachingReviewAuditResponse>> auditsByTask = new ConcurrentHashMap<>();

    @Override
    public TeachingReviewAuditResponse append(TeachingReviewAuditResponse audit) {
        auditsByTask.compute(audit.taskId(), (taskId, current) -> {
            List<TeachingReviewAuditResponse> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.add(audit);
            return List.copyOf(next);
        });
        return audit;
    }

    @Override
    public List<TeachingReviewAuditResponse> list(String taskId) {
        return auditsByTask.getOrDefault(taskId, List.of()).stream()
                .sorted(Comparator.comparing(TeachingReviewAuditResponse::createdAt))
                .toList();
    }
}
