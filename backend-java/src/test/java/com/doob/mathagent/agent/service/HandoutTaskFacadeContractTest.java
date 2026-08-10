package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.mq.InMemoryLectureTaskOutboxStore;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.LectureTaskSubmissionService;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import java.util.List;
import java.util.Optional;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the retired writing entry point on the teaching-task request contract instead of creating a second workflow.
 */
class HandoutTaskFacadeContractTest {

    @Test
    void mapsWritingRequestToOneDeterministicTeachingTaskRequest() {
        MultiAgentWritingRequest writing = new MultiAgentWritingRequest(
                "Prepare a teacher handout", "Find the angle between two lines", List.of("evidence-a", "evidence-b"),
                false, "openai", "gpt-5.6-luna");

        TeachingTaskRequest task = HandoutTaskFacade.toTeachingTaskRequest(writing);

        assertThat(task.clientRequestId()).startsWith("writing-");
        assertThat(task.questionText()).isEqualTo("Find the angle between two lines");
        assertThat(task.learningGoal()).isEqualTo("Prepare a teacher handout");
        assertThat(task.evidenceLimit()).isEqualTo(2);
        assertThat(task.aiProviderName()).isEqualTo("openai");
        assertThat(task.aiModelCode()).isEqualTo("gpt-5.6-luna");
    }

    @Test
    void projectsEveryLegacyOperationToTheSameTeachingTaskId() {
        InMemoryTeachingTaskStore taskStore = new InMemoryTeachingTaskStore();
        InMemoryLectureTaskOutboxStore outboxStore = new InMemoryLectureTaskOutboxStore();
        LectureTaskSubmissionService submission = new LectureTaskSubmissionService(taskStore, outboxStore);
        // This constructor deliberately keeps worker execution asynchronous. The test exercises durable state routing
        // only, so no provider, Java AI gateway, or synthetic handout result participates in the assertion.
        TeachingWorkflowService workflow = new TeachingWorkflowService(
                Path.of("."), null, taskStore, null, null, new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(), Optional.empty(), Optional.empty(), Runnable::run);
        HandoutTaskFacade facade = new HandoutTaskFacade(
                submission, workflow, new TeachingHandoutPdfExportService());
        RequestSubject subject = new RequestSubject("tenant-a", "teacher", "teacher-1", "device-a");
        MultiAgentWritingRequest request = new MultiAgentWritingRequest(
                "Teacher handout", "Prove the triangle angle relation", List.of("evidence-a"),
                false, "openai", "gpt-5.6-terra");

        var created = facade.submit(request, subject);
        String taskId = created.workflowId();
        TeachingTaskRequest teachingRequest = HandoutTaskFacade.toTeachingTaskRequest(request);
        TeachingRequestContext context = new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-a");
        taskStore.save(context.ownerKey(), context.idempotencyKey(teachingRequest.clientRequestId()),
                taskStore.findByTaskId(taskId).orElseThrow()
                        .withReviewStatus(TeachingTaskStatus.FAILED, "retryable transport interruption"));

        assertThat(facade.get(taskId, subject).workflowId()).isEqualTo(taskId);
        assertThat(facade.artifact(taskId, subject).workflowId()).isEqualTo(taskId);
        assertThat(facade.traces(taskId, subject).workflowId()).isEqualTo(taskId);
        assertThat(facade.export(taskId, "markdown", "", "", subject).workflowId()).isEqualTo(taskId);
        assertThat(facade.resume(taskId, subject).workflowId()).isEqualTo(taskId);
        assertThat(taskStore.findByTaskId(taskId).orElseThrow().status()).isEqualTo(TeachingTaskStatus.RUNNING);
        // One creation event and one retry event reference the sole durable teaching task, never a legacy workflow row.
        assertThat(outboxStore.pending()).hasSize(2).allSatisfy(event -> assertThat(event.taskId()).isEqualTo(taskId));
    }
}
