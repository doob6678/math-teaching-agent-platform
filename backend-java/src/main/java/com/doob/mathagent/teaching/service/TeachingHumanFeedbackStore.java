package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import java.util.List;

/**
 * Store boundary for human feedback records attached to teaching tasks.
 */
public interface TeachingHumanFeedbackStore {

    /**
     * Saves one feedback record for a backend-owned task.
     *
     * @param ownerKey normalized task owner key
     * @param feedback feedback record
     * @return saved feedback record
     */
    TeachingHumanFeedbackResponse save(String ownerKey, TeachingHumanFeedbackResponse feedback);

    /**
     * Lists feedback records visible to the normalized owner key for one task.
     *
     * @param ownerKey normalized task owner key
     * @param taskId teaching task id
     * @return matching feedback records in creation order
     */
    List<TeachingHumanFeedbackResponse> list(String ownerKey, String taskId);
}
