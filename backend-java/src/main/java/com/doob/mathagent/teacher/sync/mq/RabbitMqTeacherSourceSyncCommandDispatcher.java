package com.doob.mathagent.teacher.sync.mq;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Publishes source-sync commands and waits for RabbitMQ's broker confirmation before returning to the caller. */
@Service
public class RabbitMqTeacherSourceSyncCommandDispatcher implements TeacherSourceSyncCommandDispatcher {

    private final RabbitTemplate rabbitTemplate;
    private final TeacherSourceSyncRabbitProperties properties;

    /** Creates the RabbitMQ-backed dispatcher. */
    public RabbitMqTeacherSourceSyncCommandDispatcher(
            @Qualifier("teacherSourceSyncRabbitTemplate") RabbitTemplate rabbitTemplate,
            TeacherSourceSyncRabbitProperties properties) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    /**
     * Sends one command under its durable job id. A negative or missing broker confirm is surfaced to the already
     * authorized caller so it can retry; the job stays queued and is never incorrectly reported as executing.
     */
    @Override
    public void dispatch(TeacherSourceSyncCommand command) {
        requireCommand(command);
        CorrelationData correlation = new CorrelationData(command.jobId());
        rabbitTemplate.convertAndSend(properties.exchange(), properties.routingKey(), command, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.publisherConfirmTimeoutMilliseconds(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ rejected teacher source sync command: " + confirm.getReason());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming teacher source sync command", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Timed out confirming teacher source sync command", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("Failed to publish teacher source sync command", exception.getCause());
        }
    }

    private static void requireCommand(TeacherSourceSyncCommand command) {
        if (command == null || command.schemaVersion() != TeacherSourceSyncCommand.CURRENT_SCHEMA_VERSION
                || command.action() == null || command.action().isBlank()
                || command.tenantId() == null || command.tenantId().isBlank()
                || command.subjectRole() == null || command.subjectRole().isBlank()
                || command.subjectId() == null || command.subjectId().isBlank()
                || command.documentId() == null || command.documentId().isBlank()
                || command.jobId() == null || command.jobId().isBlank()) {
            throw new IllegalArgumentException("A complete current-version teacher source sync command is required");
        }
    }
}
