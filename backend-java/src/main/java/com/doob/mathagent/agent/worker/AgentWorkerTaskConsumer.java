package com.doob.mathagent.agent.worker;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.service.MultiAgentWritingService;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

    private final AgentWorkerTaskStore store;
    private final AgentWorkerTaskPublisher publisher;
    private final MultiAgentWritingService writingService;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    /** Creates the Worker message consumer with only Worker-owned infrastructure dependencies. */
    public AgentWorkerTaskConsumer(
            AgentWorkerTaskStore store,
            AgentWorkerTaskPublisher publisher,
            MultiAgentWritingService writingService,
            ObjectMapper objectMapper,
            Environment environment) {
        this.store = store;
        this.publisher = publisher;
        this.writingService = writingService;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /** Handles one opaque command and delegates stage ordering to the control-plane workflow service. */
    @RabbitListener(queues = AgentWorkerRabbitConfiguration.QUEUE, containerFactory = "agentWorkerRabbitListenerFactory")
    public void consume(AgentWorkerTaskCommand command) {
        AgentWorkerTask task = null;
        long startedNanos = System.nanoTime();
        try {
            task = claim(command);
            if (task == null) {
                log.info("agent_worker_task_not_claimed taskId={}", command.taskId());
                return;
            }
            log.info("agent_worker_stage_started taskId={} workflowId={} stageCode={} attempt={}",
                    task.taskId(), task.workflowId(), task.stageCode(), task.attempt());
            executeStage(task);
            if (!store.complete(task.taskId(), task.leaseToken())) {
                throw new IllegalStateException("Agent Worker task lease was lost before completion");
            }
            log.info("agent_worker_stage_completed taskId={} workflowId={} stageCode={} latencyMs={}",
                    task.taskId(), task.workflowId(), task.stageCode(), elapsedMillis(startedNanos));
        } catch (Exception exception) {
            handleFailure(task, exception, elapsedMillis(startedNanos));
        }
    }

    private AgentWorkerTask claim(AgentWorkerTaskCommand command) {
        String workerId = environment.getProperty("math-agent.agent-worker.runtime.worker-id", "local-agent-worker");
        long leaseSeconds = Long.parseLong(environment.getProperty("math-agent.agent-worker.runtime.lease-seconds", "300"));
        return store.claim(command.taskId(), workerId, Instant.now().plusSeconds(leaseSeconds));
    }

    private void executeStage(AgentWorkerTask task) throws Exception {
        JsonNode payload = objectMapper.readTree(task.requestJson());
        MultiAgentWritingRequest request = objectMapper.treeToValue(
                payload.required("request"), MultiAgentWritingRequest.class);
        RequestSubject subject = objectMapper.treeToValue(payload.required("subject"), RequestSubject.class);
        writingService.executeDispatchedStage(task.workflowId(), task.stageCode(), request, subject);
    }

    private void handleFailure(AgentWorkerTask task, Exception exception, long latencyMs) {
        if (task != null) {
            // Keep the durable task's safe failure summary and an operator-visible boundary log together.
            // Never log prompt/source contents: workflow and stage identifiers are sufficient to correlate traces.
            log.warn("agent_worker_stage_failed taskId={} workflowId={} stageCode={} attempt={} latencyMs={} errorType={} message={}",
                    task.taskId(), task.workflowId(), task.stageCode(), task.attempt(),
                    latencyMs, exception.getClass().getSimpleName(), safeMessage(exception));
            int maximumAttempts = Integer.parseInt(
                    environment.getProperty("math-agent.agent-worker.maximum-attempts", "3"));
            AgentWorkerTask retry = store.failOrRequeue(task, exception.getMessage(), maximumAttempts);
            if (retry != null) {
                // A fresh RabbitMQ delivery will issue a fresh lease token before retrying the model call.
                publisher.publish(retry);
                return;
            }
            if (task.attempt() >= maximumAttempts) {
                markWorkflowFailed(task, exception);
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

    /** Keeps the workflow state consistent with a terminal durable task so users can invoke resume explicitly. */
    private void markWorkflowFailed(AgentWorkerTask task, Exception exception) {
        try {
            JsonNode payload = objectMapper.readTree(task.requestJson());
            RequestSubject subject = objectMapper.treeToValue(payload.required("subject"), RequestSubject.class);
            writingService.failDispatchedStage(task.workflowId(), subject, exception.getMessage());
        } catch (Exception stateException) {
            exception.addSuppressed(stateException);
        }
    }
}
