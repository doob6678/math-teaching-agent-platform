package com.doob.mathagent.teaching.vo;

import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowEvent;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import java.util.List;

/**
 * Frontend-safe, durable task snapshot emitted over Server-Sent Events.
 *
 * <p>It excludes raw AI output and LaTeX bodies because the event stream is for progress only. Completed preview and
 * editing endpoints reload the full owned task after normal authorization.</p>
 *
 * @param taskId owned task ID
 * @param status durable workflow state
 * @param nodes current visible DAG nodes
 * @param workflowEvents current visible event summary
 * @param evidence compact, source-locatable evidence only
 * @param stageTimings completed stage timing rows
 * @param versions whether the three artifacts are actually ready
 * @param errorMessage safe failure reason when the task failed
 */
public record TeachingTaskProgressResponse(
        String taskId,
        TeachingTaskStatus status,
        List<TeachingWorkflowNode> nodes,
        List<TeachingWorkflowEvent> workflowEvents,
        List<TeachingEvidence> evidence,
        List<TeachingTaskResponse.StageTiming> stageTimings,
        VersionAvailability versions,
        String errorMessage) {

    /** Readiness flags avoid representing an absent version as an empty completed artifact. */
    public record VersionAvailability(boolean teacherReady, boolean studentReady, boolean lectureReady) {
    }

    /** Converts a full durable task into a transport-safe progress payload. */
    public static TeachingTaskProgressResponse from(TeachingTaskResponse task) {
        List<TeachingEvidence> safeEvidence = (task.evidence() == null ? List.<TeachingEvidence>of() : task.evidence()).stream()
                .map(item -> new TeachingEvidence(
                        "", "", "", 0, "", "", "", "", "", "", "", List.of()))
                .toList();
        List<TeachingTaskResponse.StageTiming> safeTimings = (task.stageTimings() == null
                ? List.<TeachingTaskResponse.StageTiming>of() : task.stageTimings()).stream()
                .map(timing -> new TeachingTaskResponse.StageTiming("", timing.elapsedMs()))
                .toList();
        TeachingTaskResponse studentSafe = task.studentSafe();
        return new TeachingTaskProgressResponse(
                task.taskId(),
                task.status(),
                studentSafe.nodes(),
                studentSafe.workflowEvents(),
                safeEvidence,
                safeTimings,
                new VersionAvailability(
                        hasText(task.teacherHandoutLatex()) || hasText(task.handoutLatex()),
                        hasText(task.studentHandoutLatex()),
                        hasText(task.lectureHandoutLatex())),
                "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
