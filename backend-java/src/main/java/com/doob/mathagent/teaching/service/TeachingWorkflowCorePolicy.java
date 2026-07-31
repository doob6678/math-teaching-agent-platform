package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.QuestionBankSearchText;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teaching.TeachingDraftSectionCollector;
import com.doob.mathagent.teaching.TeachingDraftMergeResult;
import com.doob.mathagent.teaching.TeachingDraftMerger;
import com.doob.mathagent.teaching.TeachingDraftReview;
import com.doob.mathagent.teaching.TeachingDraftReviewCollector;
import com.doob.mathagent.teaching.TeachingDraftSections;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingHandoutVersionCollector;
import com.doob.mathagent.teaching.TeachingHandoutVersions;
import com.doob.mathagent.teaching.TeachingKnowledgePointPack;
import com.doob.mathagent.teaching.TeachingReactStep;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.TeachingReviewPolicy;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowEvent;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ProgressPhase;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ModelExplanationUnit;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.ModelExplanationHeader;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.StageTimer;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.LabelPosition;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.LabeledDraftBlock;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.EvidencePack;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.TimedEvidence;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentContext;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentBranch;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentTiming;
import com.doob.mathagent.teaching.service.TeachingWorkflowService.QuestionAgentBatch;
import static com.doob.mathagent.teaching.service.TeachingWorkflowService.*;

/**
 * TeachingWorkflowCorePolicy owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates corepolicy rules.
 */
final class TeachingWorkflowCorePolicy {
    private TeachingWorkflowCorePolicy() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    /** Restores the original evidence request from the durable CREATED-node audit line. */
    static int evidenceLimitForResume(TeachingTaskResponse task) {
        if (task != null && task.nodes() != null) {
            for (TeachingWorkflowNode node : task.nodes()) {
                if (!"LEARNING_GOAL".equals(node.code()) || node.summary() == null) {
                    continue;
                }
                Matcher matcher = Pattern.compile("本轮证据目标：(\\d+) 条").matcher(node.summary());
                if (matcher.find()) {
                    try {
                        return Math.max(1, Integer.parseInt(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                        // Legacy/corrupt audit text uses the explicit compatibility default below.
                    }
                }
            }
        }
        return RESUME_EVIDENCE_LIMIT;
    }


    /**
     * Treats a completed workflow with teacher-only placeholder content as resumable rather than falsely final.
     *
     * <p>PDF publication runs after workflow persistence, so a three-version task can be marked completed while the
     * teacher preview correctly rejects this explicit placeholder. Reusing the durable evidence/AI draft is safer and
     * cheaper than asking users to submit a duplicate task; normal completed tasks remain immutable.</p>
     */
    static boolean hasRecoverableTeacherPublicationIssue(TeachingTaskResponse task) {
        if (task == null || task.status() != TeachingTaskStatus.COMPLETED) {
            return false;
        }
        String teacherLatex = task.teacherHandoutLatex() == null ? "" : task.teacherHandoutLatex();
        return teacherLatex.contains("题库未提供可核验答案")
                || teacherLatex.contains("需教师补充后使用")
                // These source glyphs become square boxes in the configured CJK print font. Permit exactly the
                // existing completed task to re-enter rendering after the safe Unicode-to-LaTeX conversion ships;
                // normal completed tasks remain immutable and never trigger a costly duplicate model call.
                || teacherLatex.contains("△")
                || teacherLatex.contains("∠");
    }


    /** Converts the persisted public memory summary back to the internal reuse response for a resumed run. */
    static StudentMemoryResponse fromMemoryReuse(TeachingTaskResponse.MemoryReuse memory) {
        return new StudentMemoryResponse(
                memory.reused(), memory.memoryId(), memory.reuseScope(), memory.answer(), memory.similarity(),
                memory.reason(), List.of());
    }


    /** Detects a completed retrieval barrier even when the valid result set is empty. */
    static boolean evidenceCheckpointComplete(TeachingTaskResponse checkpoint) {
        return checkpoint.nodes().stream()
                .filter(node -> Set.of(
                        "PUBLIC_TEXTBOOK_RETRIEVAL",
                        "QUESTION_BANK_RETRIEVAL",
                        "TEACHER_RESOURCE_RETRIEVAL").contains(node.code()))
                .allMatch(node -> "completed".equalsIgnoreCase(node.status()) || "skipped".equalsIgnoreCase(node.status()));
    }


    /** Forces a real re-query when source synchronization repaired a task's rejected teacher evidence. */
    static boolean requiresFreshEvidence(TeachingTaskResponse checkpoint) {
        if (checkpoint == null) {
            return false;
        }
        String teacherLatex = checkpoint.teacherHandoutLatex() == null ? "" : checkpoint.teacherHandoutLatex();
        return teacherLatex.contains("题库未提供可核验答案")
                || teacherLatex.contains("需教师补充后使用")
                || teacherLatex.contains("△")
                || teacherLatex.contains("∠");
    }


    /** Marks a failed snapshot as running while retaining already completed visible progress. */
    static TeachingTaskResponse runningSnapshot(TeachingTaskResponse task) {
        return new TeachingTaskResponse(
                task.taskId(), task.clientRequestId(), task.tenantId(), task.subjectType(), task.subjectId(),
                task.selectedTemplate(), TeachingTaskStatus.RUNNING, task.questionText(), task.learningGoal(), task.watermarkText(),
                task.nodes(), task.workflowEvents(), task.reactTrace(), task.evidence(), task.handoutLatex(),
                task.teacherHandoutLatex(), task.studentHandoutLatex(), task.lectureHandoutLatex(),
                task.interactiveSuggestions(), task.memoryReuse(), task.stageTimings(), task.aiDraft(),
                task.draftSections(), task.draftReview(), task.mergeResult(), null);
    }


    /** Records the failure without erasing the last durable boundary or source trace. */
    static TeachingTaskResponse failedSnapshot(TeachingTaskResponse task, Throwable failure) {
        String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "教学任务执行失败"
                : failure.getMessage().strip();
        return new TeachingTaskResponse(
                task.taskId(), task.clientRequestId(), task.tenantId(), task.subjectType(), task.subjectId(),
                task.selectedTemplate(), TeachingTaskStatus.FAILED, task.questionText(), task.learningGoal(), task.watermarkText(),
                task.nodes(), task.workflowEvents(), task.reactTrace(), task.evidence(), task.handoutLatex(),
                task.teacherHandoutLatex(), task.studentHandoutLatex(), task.lectureHandoutLatex(),
                task.interactiveSuggestions(), task.memoryReuse(), task.stageTimings(), task.aiDraft(),
                task.draftSections(), task.draftReview(), task.mergeResult(), message);
    }


    /** Normalizes the externally selected version before applying role and content guards. */
    static String normalizeHandoutVersion(String version) {
        if ("teacher".equalsIgnoreCase(version)) {
            return "teacher";
        }
        if ("student".equalsIgnoreCase(version)) {
            return "student";
        }
        if ("lecture".equalsIgnoreCase(version)) {
            return "lecture";
        }
        throw new IllegalArgumentException("Unsupported handout version");
    }


    /**
     * Accepts a clean draft and a draft whose deterministic review patches were all applied.
     *
     * <p>{@code MERGED} means every recorded finding was resolved by the Java-owned sanitizer. Treating only
     * {@code READY} as successful made safe student-leakage and lecture-card patches fail every otherwise valid
     * lecture. {@code NEEDS_ATTENTION} remains blocked because at least one finding was not resolved.</p>
     */
    static boolean passedAutomaticReview(TeachingDraftMergeResult mergeResult) {
        if (mergeResult == null || mergeResult.status() == null) {
            return false;
        }
        return "READY".equalsIgnoreCase(mergeResult.status())
                || "MERGED".equalsIgnoreCase(mergeResult.status());

    }


    /**
     * Generates the independent versions only from the common reviewed draft.  This has no model or retrieval call,
     * so approval is deterministic, retryable per version, and cannot incur another provider charge.
     */
    static TeachingHandoutVersions renderHandoutVersions(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingKnowledgePointPack> knowledgePointPacks,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,

            TeachingTaskResponse.AiDraft aiDraft,
            TeachingDraftSections renderSections) {
        TeachingHandoutVersions versions = TeachingHandoutVersionCollector.collect(
                () -> guardHandoutLatex(
                        buildTeacherHandoutLatex(
                                request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, renderSections),
                        true),
                () -> guardHandoutLatex(
                        buildStudentHandoutLatex(
                                request, evidence, knowledgePointPacks, memoryResponse, template, aiDraft, renderSections),
                        false),
                () -> buildLectureHandoutLatex(request, knowledgePointPacks, renderSections));
        requireQualifiedRenderedQuestionCount(template, versions.teacherHandoutLatex());
        return versions;
    }
}
