package com.doob.mathagent.teaching.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowEvent;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies progress and student projections do not expose source-bound workflow details. */
class TeachingTaskProgressSafetyTest {

    @Test
    void removesTeacherWorkflowDetailsFromStudentTaskSnapshot() {
        TeachingTaskResponse task = taskWithInternalProgress();

        TeachingTaskResponse student = task.studentSafe();

        assertThat(student.evidence()).isEmpty();
        assertThat(student.reactTrace()).isEmpty();
        assertThat(student.errorMessage()).isBlank();
        assertThat(student.workflowEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isBlank();
            assertThat(event.parentEventId()).isBlank();
            assertThat(event.sourceType()).isEqualTo("system");
            assertThat(event.sourceName()).isBlank();
            assertThat(event.eventType()).isEqualTo("stage");
            assertThat(event.title()).isBlank();
            assertThat(event.summary()).isBlank();
            assertThat(event.artifactRefs()).isEmpty();
        });
        assertThat(student.nodes()).singleElement().satisfies(node -> {
            assertThat(node.code()).isEqualTo("evidence_collection");
            assertThat(node.name()).isBlank();
            assertThat(node.summary()).isBlank();
        });
        assertThat(student.stageTimings()).isEmpty();
    }

    @Test
    void removesSourceContentAndInternalDetailsFromProgressStream() {
        TeachingTaskProgressResponse progress = TeachingTaskProgressResponse.from(taskWithInternalProgress());

        assertThat(progress.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.sourceScope()).isBlank();
            assertThat(evidence.sourceTitle()).isBlank();
            assertThat(evidence.chunkId()).isBlank();
            assertThat(evidence.snippet()).isBlank();
            assertThat(evidence.imageDescription()).isBlank();
            assertThat(evidence.sourceDocumentId()).isBlank();
            assertThat(evidence.sourceType()).isBlank();
            assertThat(evidence.sourceUrl()).isBlank();
            assertThat(evidence.sourcePath()).isBlank();
            assertThat(evidence.assetIds()).isEmpty();
        });
        assertThat(progress.workflowEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isBlank();
            assertThat(event.sourceName()).isBlank();
            assertThat(event.eventType()).isEqualTo("stage");
            assertThat(event.title()).isBlank();
            assertThat(event.summary()).isBlank();
            assertThat(event.artifactRefs()).isEmpty();
        });
        assertThat(progress.errorMessage()).isBlank();
    }

    private static TeachingTaskResponse taskWithInternalProgress() {
        TeachingEvidence evidence = new TeachingEvidence(
                "TEACHER_RESOURCE", "教师内部资料", "block-17", 3, "可见的来源摘要", "",
                "图片说明", "document-7", "pdf", "https://internal.example/source", "/private/source.pdf", List.of("asset-9"));
        TeachingWorkflowEvent event = new TeachingWorkflowEvent(
                "event-1", "parent-1", "tool", "EvidenceCollector", "evidence", "completed",
                "教师资料检索", "教师内部资料: 可见的来源摘要", List.of("document-7", "asset-9"));
        TeachingWorkflowNode node = new TeachingWorkflowNode(
                "evidence_collection", "来源证据", "completed", "教师内部资料检索完成");
        return new TeachingTaskResponse(
                "task-1", "client-1", "school-a", "student", "student-1", null,
                TeachingTaskStatus.RUNNING, "排列组合", "排列组合", "数学讲义", List.of(node), List.of(event),
                List.of(), List.of(evidence), "", "", "学生作答区", "", List.of(), null, List.of(),
                null, null, null, null, "");
    }
}
