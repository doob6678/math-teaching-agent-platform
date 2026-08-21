package com.doob.mathagent.teaching.mq;

import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Consumes an opaque task ID, claims its MySQL lease, then runs the existing lecture DAG exactly once. */
@Component
public class LectureTaskConsumer {
    private static final Logger log = LoggerFactory.getLogger(LectureTaskConsumer.class);
    private static final ScheduledExecutorService LEASE_HEARTBEAT_EXECUTOR =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "lecture-task-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    private final LectureTaskLeaseStore store;
    private final LectureTaskRetryCoordinator retryCoordinator;
    private final TeachingWorkflowService workflowService;
    private final Environment environment;

    public LectureTaskConsumer(
            LectureTaskLeaseStore store,
            LectureTaskRetryCoordinator retryCoordinator,
            TeachingWorkflowService workflowService,
            Environment environment) {
        this.store = store;
        this.retryCoordinator = retryCoordinator;
        this.workflowService = workflowService;
        this.environment = environment;
    }
    @RabbitListener(queues = LectureTaskRabbitConfiguration.QUEUE, containerFactory = "lectureTaskRabbitListenerFactory")
    public void consume(String taskId) {
        String workerId = environment.getProperty("math-agent.teaching.lecture-task.worker-id", "local-lecture-worker");
        long leaseSeconds = Long.parseLong(environment.getProperty("math-agent.teaching.lecture-task.lease-seconds", "900"));
        log.info("lecture_task_received taskId={} workerId={}", taskId, workerId);
        LectureTaskLease lease = store.tryAcquire(taskId, workerId, Instant.now(), Duration.ofSeconds(leaseSeconds));
        if (lease == null) {
            // This is a normal duplicate-delivery outcome, but it must remain visible when a UI resume appears stuck.
            log.info("lecture_task_lease_not_acquired taskId={} workerId={}", taskId, workerId);
            return;
        }
        log.info("lecture_task_lease_acquired taskId={} workerId={} attempt={} leaseToken={} expiresAt={} leaseSeconds={}",
                taskId, workerId, lease.retryCount(), tokenFingerprint(lease.token()), lease.expiresAt(), leaseSeconds);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(lease, leaseSeconds);
        try {
            workflowService.executeQueued(taskId, lease);
        } catch (TeachingWorkflowService.LeaseLostException exception) {
            // 陈旧 Worker 已失去围栏所有权，只确认旧消息，不能覆盖、重投或触发死信。
            log.info("lecture_task_lease_lost taskId={} workerId={} attempt={} leaseToken={}",
                    taskId, workerId, lease.retryCount(), tokenFingerprint(lease.token()));
            return;
        } catch (Exception exception) {
            int maximumAttempts = Integer.parseInt(environment.getProperty("math-agent.teaching.lecture-task.maximum-attempts", "3"));
            LectureTaskLeaseStore.FailureOutcome outcome = retryCoordinator.recordFailure(
                    taskId, lease, exception, maximumAttempts);
            if (outcome == LectureTaskLeaseStore.FailureOutcome.LEASE_LOST) {
                log.info("lecture_task_failure_lease_lost taskId={} workerId={} attempt={} leaseToken={} errorType={}",
                        taskId, workerId, lease.retryCount(), tokenFingerprint(lease.token()),
                        exception.getClass().getSimpleName());
                return;
            }
            if (outcome == LectureTaskLeaseStore.FailureOutcome.RETRYING) {
                log.warn("lecture_task_retry_scheduled taskId={} workerId={} attempt={} leaseToken={} maximumAttempts={} errorType={}",
                        taskId, workerId, lease.retryCount(), tokenFingerprint(lease.token()), maximumAttempts,
                        exception.getClass().getSimpleName());
                return;
            }
            log.error("lecture_task_terminal_failure taskId={} workerId={} attempt={} leaseToken={} maximumAttempts={} errorType={}",
                    taskId, workerId, lease.retryCount(), tokenFingerprint(lease.token()), maximumAttempts,
                    exception.getClass().getSimpleName());
            throw new AmqpRejectAndDontRequeueException("Lecture task failed", exception);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(LectureTaskLease lease, long leaseSeconds) {
        long configuredHeartbeat = Long.parseLong(environment.getProperty(
                "math-agent.teaching.lecture-task.heartbeat-milliseconds", "15000"));
        long interval = Math.max(1000L, Math.min(configuredHeartbeat, Duration.ofSeconds(leaseSeconds).toMillis() / 3));
        return LEASE_HEARTBEAT_EXECUTOR.scheduleAtFixedRate(
                () -> {
                    boolean renewed = store.renew(lease, Instant.now().plusSeconds(leaseSeconds));
                    if (!renewed) {
                        log.warn("lecture_task_lease_renew_failed taskId={} workerId={} attempt={} leaseToken={} requestedExpiry={}",
                                lease.taskId(), lease.workerId(), lease.retryCount(), tokenFingerprint(lease.token()),
                                Instant.now().plusSeconds(leaseSeconds));
                    }
                }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Identifies one lease lifecycle in logs without exposing the bearer-like fencing token. */
    private static String tokenFingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "none";
        }
        return Integer.toHexString(token.hashCode());
    }
}
