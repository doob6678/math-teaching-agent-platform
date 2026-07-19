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

/**
 * Worker-side boundary for one distributed Agent stage.
 *
 * <p>The listener must claim MySQL ownership before deserializing a payload. Consequently, a duplicate AMQP
 * delivery sees no claimable task and safely acknowledges without executing a second model call.</p>
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.agent-worker.runtime", name = "enabled", havingValue = "true")
public class AgentWorkerTaskConsumer {

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
        try {
            task = claim(command);
            if (task == null) {
                return;
            }
            executeStage(task);
            if (!store.complete(task.taskId(), task.leaseToken())) {
                throw new IllegalStateException("Agent Worker task lease was lost before completion");
            }
        } catch (Exception exception) {
            handleFailure(task, exception);
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

    private void handleFailure(AgentWorkerTask task, Exception exception) {
        if (task != null) {
            int maximumAttempts = Integer.parseInt(
                    environment.getProperty("math-agent.agent-worker.maximum-attempts", "3"));
            AgentWorkerTask retry = store.failOrRequeue(task, exception.getMessage(), maximumAttempts);
            if (retry != null) {
                // A fresh RabbitMQ delivery will issue a fresh lease token before retrying the model call.
                publisher.publish(retry);
                return;
            }
        }
        // Terminal failures are intentionally dead-lettered after the durable task record has been updated.
        throw new AmqpRejectAndDontRequeueException("Agent Worker task failed", exception);
    }
}
