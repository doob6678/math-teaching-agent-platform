package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory feedback store used for local development and tests before MyBatis persistence is introduced.
 */
public class InMemoryTeachingHumanFeedbackStore implements TeachingHumanFeedbackStore {

    private final Map<String, List<TeachingHumanFeedbackResponse>> feedbackByOwnerTask = new ConcurrentHashMap<>();

    /**
     * Saves one feedback record under the backend owner key and task id.
     */
    @Override
    public TeachingHumanFeedbackResponse save(String ownerKey, TeachingHumanFeedbackResponse feedback) {
        feedbackByOwnerTask.compute(key(ownerKey, feedback.taskId()), (ignored, existing) -> {
            List<TeachingHumanFeedbackResponse> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(feedback);
            return next;
        });
        return feedback;
    }

    /**
     * Lists feedback records for one owner and task in creation order.
     */
    @Override
    public List<TeachingHumanFeedbackResponse> list(String ownerKey, String taskId) {
        return feedbackByOwnerTask.getOrDefault(key(ownerKey, taskId), List.of()).stream()
                .sorted(Comparator.comparing(TeachingHumanFeedbackResponse::createdAt))
                .toList();
    }

    /**
     * Builds a compound key that keeps identical task ids isolated across tenants and subjects.
     */
    private static String key(String ownerKey, String taskId) {
        return ownerKey + ":" + taskId;
    }
}
