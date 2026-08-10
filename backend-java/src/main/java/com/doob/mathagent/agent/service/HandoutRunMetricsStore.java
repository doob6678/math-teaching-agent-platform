package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.mapper.HandoutRunMetricsMapper;
import com.doob.mathagent.agent.worker.AgentWorkerTask;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Persists Java's queue, lease and ACK timing without giving the Java control plane ownership of Python usage rows.
 * Python fills the same run row with graph/load data; these updates only cover infrastructure boundaries.
 */
@Service
public class HandoutRunMetricsStore {
    private final HandoutRunMetricsMapper mapper;

    public HandoutRunMetricsStore(HandoutRunMetricsMapper mapper) {
        this.mapper = mapper;
    }

    /** Records the control-plane submission/enqueue boundary before RabbitMQ publication. */
    public void recordEnqueued(String runId, String taskId, Instant enqueuedAt) {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank() || enqueuedAt == null) return;
        mapper.recordEnqueued(new LifecycleRow(runId, taskId, enqueuedAt, enqueuedAt, null, null, null));
    }

    /** Records publication-gate entry without coupling the PDF renderer to workflow state transitions. */
    public void recordPublicationGate(String runId, String taskId, Instant gateAt) {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank() || gateAt == null) return;
        mapper.recordPublicationGate(new LifecycleRow(runId, taskId, null, null, gateAt, null, null));
    }

    /** Records XeLaTeX start and elapsed time; failures remain visible through the existing terminal status. */
    public void recordPdf(String runId, String taskId, Instant startedAt, Instant finishedAt, long elapsedMs) {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) return;
        mapper.recordPdf(new LifecycleRow(runId, taskId, null, null, null, startedAt, Math.max(0L, elapsedMs)));
    }

    /** Records claim time and queue wait before any payload is deserialized or AI work begins. */
    public void recordClaim(AgentWorkerTask task, Instant claimedAt) {
        if (task == null) return;
        Instant createdAt = task.createdAt();
        long queueWait = createdAt == null ? 0L : Math.max(0L, Duration.between(createdAt, claimedAt).toMillis());
        mapper.recordClaim(new MetricsRow(task.workflowId(), task.taskId(), "RUNNING", claimedAt, queueWait,
                task.attempt(), null, null, null, null, null, claimedAt));
    }

    /** Records the measured time spent acquiring the durable task lease after AMQP delivery. */
    public void recordLeaseWait(AgentWorkerTask task, long leaseWaitMs, Instant measuredAt) {
        if (task == null || measuredAt == null) return;
        mapper.recordLeaseWait(new LifecycleRow(
                task.workflowId(), task.taskId(), null, null, null, null, Math.max(0L, leaseWaitMs)));
    }

    /** Increments the DLQ counter only after the final retry has been exhausted. */
    public void recordDeadLetter(AgentWorkerTask task, Instant deadLetteredAt) {
        if (task == null || deadLetteredAt == null) return;
        mapper.recordDeadLetter(new LifecycleRow(task.workflowId(), task.taskId(), null, null, null, deadLetteredAt, null));
    }

    /** Records a completed or failed lease boundary and the measured end-to-end ACK latency. */
    public void recordTerminal(AgentWorkerTask task, String status, long elapsedMs, Instant terminalAt) {
        if (task == null) return;
        Instant completedAt = "COMPLETED".equals(status) ? terminalAt : null;
        Instant failedAt = "COMPLETED".equals(status) ? null : terminalAt;
        mapper.recordTerminal(new MetricsRow(task.workflowId(), task.taskId(), status, null, null, task.attempt(),
                completedAt, failedAt, terminalAt, Math.max(0L, elapsedMs), null, terminalAt));
    }

    /** Narrow SQL parameter object; keeping it here avoids exposing metrics persistence fields as a domain API. */
    public record MetricsRow(
            String runId,
            String taskId,
            String status,
            Instant claimedAt,
            Long queueWaitMs,
            Integer retryCount,
            Instant completedAt,
            Instant failedAt,
            Instant acknowledgedAt,
            Long ackLatencyMs,
            String unused,
            Instant updatedAt) {
    }

    /** SQL-only lifecycle payload; it is intentionally separate from queue/ACK fields used by existing tests. */
    public record LifecycleRow(
            String runId,
            String taskId,
            Instant submittedAt,
            Instant enqueuedAt,
            Instant publicationGateAt,
            Instant xelatexStartedAt,
            Long elapsedMs) {
    }
}
