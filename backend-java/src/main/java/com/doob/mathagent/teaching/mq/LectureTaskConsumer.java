package com.doob.mathagent.teaching.mq;

import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Consumes an opaque task ID, claims its MySQL lease, then runs the existing lecture DAG exactly once. */
@Component
public class LectureTaskConsumer {
    private final LectureTaskLeaseStore store;
    private final LectureTaskPublisher publisher;
    private final TeachingWorkflowService workflowService;
    private final Environment environment;
    public LectureTaskConsumer(LectureTaskLeaseStore store, LectureTaskPublisher publisher, TeachingWorkflowService workflowService, Environment environment) {
        this.store = store; this.publisher = publisher; this.workflowService = workflowService; this.environment = environment;
    }
    @RabbitListener(queues = LectureTaskRabbitConfiguration.QUEUE, containerFactory = "lectureTaskRabbitListenerFactory")
    public void consume(String taskId) {
        String workerId = environment.getProperty("math-agent.teaching.lecture-task.worker-id", "local-lecture-worker");
        long leaseSeconds = Long.parseLong(environment.getProperty("math-agent.teaching.lecture-task.lease-seconds", "300"));
        LectureTaskLease lease = store.tryAcquire(taskId, workerId, Instant.now(), Duration.ofSeconds(leaseSeconds));
        if (lease == null) return; // A duplicate delivery or an active owner is safely acknowledged.
        try {
            workflowService.executeQueued(taskId);
            if (!store.complete(lease)) throw new IllegalStateException("Lecture task lease was lost before completion");
        } catch (Exception exception) {
            int maximumAttempts = Integer.parseInt(environment.getProperty("math-agent.teaching.lecture-task.maximum-attempts", "3"));
            boolean retry = store.failOrRetry(lease, exception.getMessage(), maximumAttempts);
            // Persist the user-visible error only after the CAS update so snapshot persistence cannot invalidate it.
            workflowService.failQueued(taskId, exception);
            if (retry) {
                publisher.publish(taskId);
                return;
            }
            throw new AmqpRejectAndDontRequeueException("Lecture task failed", exception);
        }
    }
}
