package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.mq.LectureTaskOutboxStore;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the task snapshot and its outbox event in one MySQL transaction. */
@Service
public class LectureTaskSubmissionService {
    private final TeachingTaskStore taskStore;
    private final LectureTaskOutboxStore outboxStore;
    public LectureTaskSubmissionService(TeachingTaskStore taskStore, LectureTaskOutboxStore outboxStore) { this.taskStore = taskStore; this.outboxStore = outboxStore; }

    /** Returns a durable CREATED task; asynchronous execution starts only after the outbox publisher sends taskId. */
    @Transactional
    public TeachingTaskResponse submit(TeachingTaskRequest request, TeachingRequestContext context) {
        TeachingRequestContext normalizedContext = context.normalize();
        TeachingTaskRequest normalizedRequest = request.normalize();
        String ownerKey = normalizedContext.ownerKey();
        String idempotencyKey = normalizedContext.idempotencyKey(normalizedRequest.clientRequestId());
        Optional<TeachingTaskResponse> existing = taskStore.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();
        TeachingTaskResponse created = new TeachingTaskResponse(
                UUID.randomUUID().toString(), normalizedRequest.clientRequestId(), normalizedContext.tenantId(),
                normalizedContext.subjectType(), normalizedContext.subjectId(), null, TeachingTaskStatus.CREATED,
                normalizedRequest.questionText(), normalizedRequest.learningGoal(), normalizedRequest.watermarkText(),
                TeachingWorkflowService.initialWorkflowNodes(normalizedRequest), List.of(), List.of(), List.of(),
                "", "", "", "", List.of(), null, List.of(), null, null, null, null, null);
        TeachingTaskResponse durable = taskStore.createIfAbsent(ownerKey, idempotencyKey, created);
        // A concurrent duplicate returns its existing task. The unique outbox key still makes this idempotent.
        outboxStore.enqueue(durable.taskId());
        return durable;
    }

    /** Persists a resume transition and a distinct outbox event so an already-published create event is never reused. */
    @Transactional
    public TeachingTaskResponse resume(String taskId, TeachingRequestContext context, TeachingWorkflowService workflowService) {
        TeachingTaskResponse resumed = workflowService.resume(taskId, context);
        outboxStore.enqueue(resumed.taskId(), "LECTURE_TASK_RESUMED");
        return resumed;
    }
}
