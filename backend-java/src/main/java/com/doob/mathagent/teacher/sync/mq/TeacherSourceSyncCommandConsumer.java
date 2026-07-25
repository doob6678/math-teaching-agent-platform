package com.doob.mathagent.teacher.sync.mq;

import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.util.Objects;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes source-sync work outside the request thread while letting MySQL remain the idempotency authority.
 *
 * <p>Business/provider errors are converted by the execution service to failed or paused durable job states. Only
 * malformed commands or unexpected infrastructure errors are dead-lettered for operator inspection.</p>
 */
@Component
public class TeacherSourceSyncCommandConsumer {

    private final TeacherSourceSyncExecutionService executionService;
    private final TeacherSourceSyncJobStore jobStore;

    /** Creates the consumer. */
    public TeacherSourceSyncCommandConsumer(
            TeacherSourceSyncExecutionService executionService,
            TeacherSourceSyncJobStore jobStore) {
        this.executionService = Objects.requireNonNull(executionService, "executionService is required");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore is required");
    }

    /** Processes one durable command using a bounded listener container configured by the topology configuration. */
    @RabbitListener(queues = "${math-agent.teacher.sync.rabbitmq.queue}",
            containerFactory = "teacherSourceSyncRabbitListenerContainerFactory")
    public void consume(TeacherSourceSyncCommand command) {
        try {
            validate(command);
            TeacherSourceSyncJobResponse job = jobStore.listByDocument(command.tenantId(), command.documentId()).stream()
                    .filter(candidate -> command.jobId().equals(candidate.jobId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Teacher source sync job does not exist: " + command.jobId()));
            if (!canExecute(command.action(), job.status())) {
                // A broker confirm timeout can lead a caller to retry publishing. Completed/running messages are safe
                // to acknowledge because the persisted job state proves another delivery already owns or finished it.
                return;
            }
            if (TeacherSourceSyncCommand.EXECUTE.equals(command.action())) {
                executionService.execute(command.tenantId(), command.subjectRole(), command.subjectId(),
                        command.documentId(), command.jobId());
            } else {
                executionService.resume(command.tenantId(), command.subjectRole(), command.subjectId(),
                        command.documentId(), command.jobId());
            }
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("Invalid teacher source sync command", exception);
        } catch (RuntimeException exception) {
            throw new AmqpRejectAndDontRequeueException("Unexpected teacher source sync consumer failure", exception);
        }
    }

    private static boolean canExecute(String action, String status) {
        return (TeacherSourceSyncCommand.EXECUTE.equals(action) && "queued".equalsIgnoreCase(status))
                || (TeacherSourceSyncCommand.RESUME.equals(action)
                        && ("paused".equalsIgnoreCase(status) || "AUTH_REQUIRED".equalsIgnoreCase(status)));
    }

    private static void validate(TeacherSourceSyncCommand command) {
        if (command == null || command.schemaVersion() != TeacherSourceSyncCommand.CURRENT_SCHEMA_VERSION
                || !(TeacherSourceSyncCommand.EXECUTE.equals(command.action())
                        || TeacherSourceSyncCommand.RESUME.equals(command.action()))) {
            throw new IllegalArgumentException("Unsupported teacher source sync command contract");
        }
    }
}
