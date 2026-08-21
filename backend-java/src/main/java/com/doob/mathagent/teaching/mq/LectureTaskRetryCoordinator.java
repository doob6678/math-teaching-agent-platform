package com.doob.mathagent.teaching.mq;

import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a retryable task failure together with its replacement outbox event.
 *
 * <p>The consumer must never leave a task in {@code RETRYING} without a durable publication record: a process or
 * RabbitMQ interruption after the lease CAS then remains recoverable by the outbox scheduler. Terminal failures are
 * intentionally returned to the consumer, which preserves its existing dead-letter handling after this transaction
 * has completed.</p>
 */
@Component
public class LectureTaskRetryCoordinator {
    private static final String RETRY_EVENT = "LECTURE_TASK_RETRY";

    private final TeachingWorkflowService workflowService;
    private final LectureTaskOutboxStore outboxStore;

    public LectureTaskRetryCoordinator(
            TeachingWorkflowService workflowService,
            LectureTaskOutboxStore outboxStore) {
        this.workflowService = workflowService;
        this.outboxStore = outboxStore;
    }

    @Transactional
    public LectureTaskLeaseStore.FailureOutcome recordFailure(
            String taskId,
            LectureTaskLease lease,
            Exception failure,
            int maximumAttempts) {
        LectureTaskLeaseStore.FailureOutcome outcome = workflowService.recordQueuedFailure(
                taskId, lease, failure, maximumAttempts);
        if (outcome == LectureTaskLeaseStore.FailureOutcome.RETRYING) {
            outboxStore.enqueue(taskId, RETRY_EVENT);
        }
        return outcome;
    }
}
