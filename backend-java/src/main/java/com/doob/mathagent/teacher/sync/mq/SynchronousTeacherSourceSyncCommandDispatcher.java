package com.doob.mathagent.teacher.sync.mq;

import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import java.util.Objects;

/**
 * Compatibility dispatcher used only by focused controller unit construction; production wiring always uses RabbitMQ.
 * It keeps legacy constructor tests isolated while exercising the same domain execution methods.
 */
public final class SynchronousTeacherSourceSyncCommandDispatcher implements TeacherSourceSyncCommandDispatcher {

    private final TeacherSourceSyncExecutionService executionService;

    /** Creates the compatibility dispatcher around the real execution service. */
    public SynchronousTeacherSourceSyncCommandDispatcher(TeacherSourceSyncExecutionService executionService) {
        this.executionService = Objects.requireNonNull(executionService, "executionService is required");
    }

    @Override
    public void dispatch(TeacherSourceSyncCommand command) {
        if (TeacherSourceSyncCommand.RESUME.equals(command.action())) {
            executionService.resume(command.tenantId(), command.subjectRole(), command.subjectId(),
                    command.documentId(), command.jobId());
            return;
        }
        if (!TeacherSourceSyncCommand.EXECUTE.equals(command.action())) {
            throw new IllegalArgumentException("Unsupported teacher source sync action: " + command.action());
        }
        executionService.execute(command.tenantId(), command.subjectRole(), command.subjectId(),
                command.documentId(), command.jobId());
    }
}
