package com.doob.mathagent.agent.worker;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.agent.service.HandoutRunMetricsStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-side boundary for one distributed Agent stage.
 *
 * <p>The listener must claim MySQL ownership before deserializing a payload. Consequently, a duplicate AMQP
 * delivery sees no claimable task and safely acknowledges without executing a second model call.</p>
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.agent-worker.runtime", name = "enabled", havingValue = "true")
public class AgentWorkerTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkerTaskConsumer.class);
    private static final ScheduledExecutorService LEASE_HEARTBEAT_EXECUTOR =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "agent-worker-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final AgentWorkerTaskStore store;
    private final AgentWorkerTaskDispatchService dispatchService;
    private final MultiAgentWritingService writingService;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private HandoutRunMetricsStore metricsStore;

    /** Creates the Worker message consumer with only Worker-owned infrastructure dependencies. */
    public AgentWorkerTaskConsumer(
            AgentWorkerTaskStore store,
            AgentWorkerTaskDispatchService dispatchService,
            MultiAgentWritingService writingService,
            ObjectMapper objectMapper,
            Environment environment) {
        this.store = store;
        this.dispatchService = dispatchService;
        this.writingService = writingService;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /** Injects optional telemetry so focused in-memory Worker tests remain independent of MySQL schema availability. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureMetricsStore(HandoutRunMetricsStore metricsStore) {
        this.metricsStore = metricsStore;
    }

    /** Handles one opaque command and delegates stage ordering to the control-plane workflow service. */
    @RabbitListener(queues = AgentWorkerRabbitConfiguration.QUEUE, containerFactory = "agentWorkerRabbitListenerFactory")
    public void consume(AgentWorkerTaskCommand command) {
        AgentWorkerTask task = null;
        long startedNanos = System.nanoTime();
        try {
            long leaseClaimStartedNanos = System.nanoTime();
            task = claim(command);
            if (task == null) {
                log.info("agent_worker_task_not_claimed taskId={}", command.taskId());
                return;
            }
            long queueWaitMs = task.createdAt() == null
                    ? -1L
                    : Math.max(0L, Duration.between(task.createdAt(), Instant.now()).toMillis());
            if (metricsStore != null) {
                Instant claimedAt = Instant.now();
                metricsStore.recordLeaseWait(task, elapsedMillis(leaseClaimStartedNanos), claimedAt);
                metricsStore.recordClaim(task, claimedAt);
            }
            log.info("agent_worker_stage_started taskId={} workflowId={} stageCode={} attempt={} queueWaitMs={}",
                    task.taskId(), task.workflowId(), task.stageCode(), task.attempt(), queueWaitMs);
            ScheduledFuture<?> heartbeat = scheduleHeartbeat(task);
            try {
                executeStage(task);
            } finally {
                heartbeat.cancel(false);
            }
            if (!store.complete(task.taskId(), task.leaseToken())) {
                throw new IllegalStateException("Agent Worker task lease was lost before completion");
            }
            if (metricsStore != null) {
                metricsStore.recordTerminal(task, "COMPLETED", elapsedMillis(startedNanos), Instant.now());
            }
            log.info("agent_worker_stage_completed taskId={} workflowId={} stageCode={} latencyMs={}",
                    task.taskId(), task.workflowId(), task.stageCode(), elapsedMillis(startedNanos));
        } catch (Exception exception) {
            handleFailure(task, exception, elapsedMillis(startedNanos));
        }
    }

    private AgentWorkerTask claim(AgentWorkerTaskCommand command) {
        String workerId = environment.getProperty("math-agent.agent-worker.runtime.worker-id", "local-agent-worker");
        long leaseSeconds = Long.parseLong(environment.getProperty("math-agent.agent-worker.runtime.lease-seconds", "900"));
        return store.claim(command.taskId(), workerId, Instant.now().plusSeconds(leaseSeconds));
    }

    /** Renews the lease during model and PDF work so a valid long-running request is never reclaimed mid-call. */
    private ScheduledFuture<?> scheduleHeartbeat(AgentWorkerTask task) {
        long leaseSeconds = Long.parseLong(environment.getProperty("math-agent.agent-worker.runtime.lease-seconds", "900"));
        long heartbeatMillis = Long.parseLong(environment.getProperty(
                "math-agent.agent-worker.runtime.heartbeat-milliseconds", "15000"));
        long interval = Math.max(1000L, Math.min(heartbeatMillis, Duration.ofSeconds(leaseSeconds).toMillis() / 3));
        return LEASE_HEARTBEAT_EXECUTOR.scheduleAtFixedRate(
                () -> {
                    boolean renewed = store.renew(task, Instant.now().plusSeconds(leaseSeconds));
                    if (!renewed) {
                        log.warn("agent_worker_lease_renew_failed taskId={} workflowId={} stageCode={}",
                                task.taskId(), task.workflowId(), task.stageCode());
                    }
                },
                interval,
                interval,
                TimeUnit.MILLISECONDS);
    }

    private void executeStage(AgentWorkerTask task) throws Exception {
        JsonNode payload = objectMapper.readTree(task.requestJson());
        MultiAgentWritingRequest request = objectMapper.treeToValue(
                payload.required("request"), MultiAgentWritingRequest.class);
        // The broker command and task payload are deliberately identity-free. Reloading from the workflow record
        // makes authorization resilient to redelivery and prevents JSON payload fields from overriding Java state.
        var subject = writingService.resolveWorkerSubject(task.workflowId());
        if (!AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_STAGE_CODE.equals(task.stageCode())) {
            throw new IllegalArgumentException("Unsupported retired Java handout stage: " + task.stageCode());
        }
        // Python performs every graph branch inside one lease-protected durable graph command.
        writingService.executeDispatchedPython(task.workflowId(), request, subject);
    }

    private void handleFailure(AgentWorkerTask task, Exception exception, long latencyMs) {
        if (task != null) {
            if (metricsStore != null) {
                metricsStore.recordTerminal(task, "FAILED", latencyMs, Instant.now());
            }
            // Keep the durable task's safe failure summary and an operator-visible boundary log together.
            // Never log prompt/source contents: workflow and stage identifiers are sufficient to correlate traces.
            log.warn("agent_worker_stage_failed taskId={} workflowId={} stageCode={} attempt={} latencyMs={} errorType={} message={}",
                    task.taskId(), task.workflowId(), task.stageCode(), task.attempt(),
                    latencyMs, exception.getClass().getSimpleName(), safeMessage(exception));
            int maximumAttempts = Integer.parseInt(
                    environment.getProperty("math-agent.agent-worker.maximum-attempts", "3"));
            boolean requeued = dispatchService.handleFailure(task, exception.getMessage(), maximumAttempts);
            if (requeued) {
                // The old delivery is acknowledged. A scheduler will publish the next durable dispatch generation.
                return;
            }
            if (!store.isFailed(task.taskId())) {
                // A stale worker lost its lease to a recovery owner; the newer delivery is authoritative.
                log.info("agent_worker_failure_lease_lost taskId={} workflowId={}", task.taskId(), task.workflowId());
                return;
            }
            if (metricsStore != null) {
                metricsStore.recordDeadLetter(task, Instant.now());
            }
        }
        // Terminal failures are intentionally dead-lettered after the durable task record has been updated.
        throw new AmqpRejectAndDontRequeueException("Agent Worker task failed", exception);
    }

    /** Bounds exception text so gateway responses cannot leak source content into container logs. */
    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "no message";
        return message.substring(0, Math.min(500, message.length())).replaceAll("[\\r\\n]+", " ");
    }

    /** Uses monotonic time for comparable worker-stage durations across Docker clock changes. */
    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

}
