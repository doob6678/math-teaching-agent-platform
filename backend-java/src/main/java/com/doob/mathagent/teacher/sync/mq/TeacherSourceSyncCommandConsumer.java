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
        TeacherSourceSyncJobResponse job = null;
        try {
            validate(command);
            job = jobStore.listByDocument(command.tenantId(), command.documentId()).stream()
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
            failRunningJob(job, exception);
            throw new AmqpRejectAndDontRequeueException("Invalid teacher source sync command", exception);
        } catch (RuntimeException exception) {
            /*
             * The execution service normally converts business failures itself. This boundary also covers failures
             * thrown before that conversion (for example a listener wiring or persistence exception), so a message
             * cannot leave a durable job in running forever while it is moved to the dead-letter queue.
             */
            failRunningJob(job, exception);
            throw new AmqpRejectAndDontRequeueException("Unexpected teacher source sync consumer failure", exception);
        }
    }

    private void failRunningJob(TeacherSourceSyncJobResponse job, RuntimeException exception) {
        if (job == null || !"running".equalsIgnoreCase(job.status())) {
            return;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        jobStore.save(new TeacherSourceSyncJobResponse(
                job.jobId(), job.documentId(), job.tenantId(), job.sourceType(), job.operation(),
                "failed", "consumer_failed", job.attempt(), job.createdBy(), job.stagingPath(),
                message, job.createdAt(), java.time.Instant.now().toString(), job.failure()));
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
