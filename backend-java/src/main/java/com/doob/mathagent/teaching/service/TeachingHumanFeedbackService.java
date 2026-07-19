package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingHumanFeedbackRequest;
import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for recording human review decisions on generated teaching tasks.
 */
@Service
public class TeachingHumanFeedbackService {

    private final TeachingHumanFeedbackStore store;
    private final Clock clock;

    /**
     * Creates the production service.
     *
     * @param store feedback store
     */
    @Autowired
    public TeachingHumanFeedbackService(TeachingHumanFeedbackStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates a testable service with an explicit clock.
     *
     * @param store feedback store
     * @param clock clock used for feedback timestamps
     */
    public TeachingHumanFeedbackService(TeachingHumanFeedbackStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Records feedback for a task that the controller already loaded through backend ownership checks.
     *
     * @param taskId owned task id
     * @param context backend request context
     * @param request feedback request
     * @return stored feedback response
     */
    public TeachingHumanFeedbackResponse submit(
            String taskId,
            TeachingRequestContext context,
            TeachingHumanFeedbackRequest request) {
        TeachingRequestContext normalized = context.normalize();
        TeachingHumanFeedbackRequest normalizedRequest = request.normalize();
        TeachingHumanFeedbackResponse feedback = new TeachingHumanFeedbackResponse(
                UUID.randomUUID().toString(),
                taskId,
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalizedRequest.rating(),
                normalizedRequest.decision(),
                normalizedRequest.comment(),
                normalizedRequest.reviewContext(),
                Instant.now(clock));
        return store.save(normalized.ownerKey(), feedback);
    }

    /**
     * Lists feedback for a task owned by the current backend subject.
     *
     * @param taskId owned task id
     * @param context backend request context
     * @return feedback records
     */
    public List<TeachingHumanFeedbackResponse> list(String taskId, TeachingRequestContext context) {
        return store.list(context.normalize().ownerKey(), taskId);
    }
}
