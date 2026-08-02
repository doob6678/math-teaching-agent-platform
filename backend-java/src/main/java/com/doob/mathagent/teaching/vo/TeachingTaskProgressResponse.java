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

    /** Limits event transport snippets so a progress stream cannot become a raw OCR or prompt delivery channel. */
    private static final int MAX_PROGRESS_EVIDENCE_CHARS = 360;

    /** Readiness flags avoid representing an absent version as an empty completed artifact. */
    public record VersionAvailability(boolean teacherReady, boolean studentReady, boolean lectureReady) {
    }

    /** Converts a full durable task into a transport-safe progress payload. */
    public static TeachingTaskProgressResponse from(TeachingTaskResponse task) {
        List<TeachingEvidence> compactEvidence = (task.evidence() == null ? List.<TeachingEvidence>of() : task.evidence()).stream()
                .map(item -> new TeachingEvidence(
                        item.sourceScope(),
                        item.sourceTitle(),
                        item.chunkId(),
                        item.pageNo(),
                        compactEvidenceSnippet(item.snippet()),
                        "",
                        item.imageDescription(),
                        item.sourceDocumentId(),
                        item.sourceType(),
                        item.sourceUrl(),
                        item.sourcePath(),
                        item.assetIds()))
                .toList();
        return new TeachingTaskProgressResponse(
                task.taskId(),
                task.status(),
                task.nodes() == null ? List.of() : task.nodes(),
                task.workflowEvents() == null ? List.of() : task.workflowEvents(),
                compactEvidence,
                task.stageTimings() == null ? List.of() : task.stageTimings(),
                new VersionAvailability(
                        hasText(task.teacherHandoutLatex()) || hasText(task.handoutLatex()),
                        hasText(task.studentHandoutLatex()),
                        hasText(task.lectureHandoutLatex())),
                task.errorMessage());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Removes control whitespace and internal-operational terms before emitting an evidence preview over SSE. */
    private static String compactEvidenceSnippet(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value
                .replaceAll("(?i)MODEL_CALL|JSON_PARSE|\\btokens?\\b|system prompt|debug|调试", "")
                .replaceAll("\\s+", " ")
                .strip();
        return compact.length() <= MAX_PROGRESS_EVIDENCE_CHARS
                ? compact
                : compact.substring(0, MAX_PROGRESS_EVIDENCE_CHARS).strip() + "…";
    }
}
