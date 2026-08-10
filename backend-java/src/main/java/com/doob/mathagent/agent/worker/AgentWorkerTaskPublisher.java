package com.doob.mathagent.agent.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Publishes opaque task commands and waits for RabbitMQ's correlated durable publisher confirmation. */
@Service
public class AgentWorkerTaskPublisher {
    private static final long CONFIRM_TIMEOUT_SECONDS = 10;
    private final RabbitTemplate rabbitTemplate;

    public AgentWorkerTaskPublisher(@Qualifier("agentWorkerRabbitTemplate") RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** Throws unless the broker ACKs and the mandatory route resolves to a bound queue. */
    public void publish(AgentWorkerTaskOutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.eventId());
        rabbitTemplate.convertAndSend(
                AgentWorkerRabbitConfiguration.EXCHANGE,
                event.agentCode(),
                new AgentWorkerTaskCommand(event.taskId()),
                correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ReturnedMessage returned = correlation.getReturned();
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ publisher confirm NACK: " + (confirm == null ? "timeout" : confirm.getReason()));
            }
            if (returned != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable Agent Worker task: " + returned.getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RabbitMQ publisher confirm", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Timed out waiting for RabbitMQ publisher confirm", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("RabbitMQ publisher confirm failed", exception.getCause());
        }
    }
}
