package com.doob.mathagent.teaching.vo;

import com.doob.mathagent.teaching.TeachingDraftSections;
import com.doob.mathagent.teaching.TeachingDraftMergeResult;
import com.doob.mathagent.teaching.TeachingDraftReview;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingReactStep;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingWorkflowEvent;
import com.doob.mathagent.teaching.TeachingWorkflowNode;
import java.util.List;

/**
 * 教学任务响应。
 *
 * @param taskId 后端任务 ID，前端可保存到 localStorage，离开页面后继续查询。
 * @param clientRequestId 前端幂等请求号。
 * @param tenantId 租户 ID，用于说明任务归属。
 * @param subjectType 主体类型，用于权限审计。
 * @param subjectId 主体 ID，用于私有任务隔离。
 * @param selectedTemplate backend-owned handout template applied to this task
 * @param status 任务状态。
 * @param questionText 原始题目或学习问题。
 * @param learningGoal 用户学习目标。
 * @param headerLeft 独立左页眉，兼容旧任务时回落到 watermarkText。
 * @param headerRight 独立右页眉。
 * @param footerLeft 独立左页脚。
 * @param footerRight 独立右页脚。
 * @param nodes 固定 DAG 节点执行结果。
 * @param workflowEvents 可恢复工作流事件；比 nodes 更适合前端过程流和后续 event 表持久化。
 * @param reactTrace 解题 ReAct 轨迹。
 * @param evidence 使用的证据列表。
 * @param handoutLatex LaTeX 讲义草稿。
 * @param interactiveSuggestions 后续交互建议。
 * @param memoryReuse 学生记忆复用决策，用于前端展示是否跳过重复生成。
 * @param stageTimings 教学任务每个阶段的耗时统计，用于前端状态面板和性能排查。
 * @param errorMessage 失败原因；成功时为空。
 */
public record TeachingTaskResponse(
        String taskId,
        String clientRequestId,
        String tenantId,
        String subjectType,
        String subjectId,
        TeachingHandoutTemplateResponse selectedTemplate,
        TeachingTaskStatus status,
        String questionText,
        String learningGoal,
        String watermarkText,
        List<TeachingWorkflowNode> nodes,
        List<TeachingWorkflowEvent> workflowEvents,
        List<TeachingReactStep> reactTrace,
        List<TeachingEvidence> evidence,
        String handoutLatex,
        String teacherHandoutLatex,
        String studentHandoutLatex,
        String lectureHandoutLatex,
        List<String> interactiveSuggestions,
        MemoryReuse memoryReuse,
        List<StageTiming> stageTimings,
        AiDraft aiDraft,
        TeachingDraftSections draftSections,
        TeachingDraftReview draftReview,
        TeachingDraftMergeResult mergeResult,
        String errorMessage,
        String headerLeft,
        String headerRight,
        String footerLeft,
        String footerRight) {

    public TeachingTaskResponse {
        // Legacy snapshots used a product name as an implicit default. Treat it as an absent user choice so a
        // re-export cannot reintroduce a brand the current task did not explicitly choose.
        watermarkText = normalizeWatermarkText(watermarkText);
        draftSections = draftSections == null
                ? new TeachingDraftSections("", "", List.of(), List.of(), List.of(), List.of())
                : draftSections;
        draftReview = draftReview == null
                ? new TeachingDraftReview("READY", List.of(), List.of())
                : draftReview;
        mergeResult = mergeResult == null
                ? new TeachingDraftMergeResult("READY", draftSections, List.of(), List.of(), List.of())
                : mergeResult;
        String normalizedWatermark = normalizeWatermarkText(watermarkText);
        headerLeft = normalizeChrome(headerLeft, normalizedWatermark);
        headerRight = normalizeChrome(headerRight, learningGoal);
        footerLeft = normalizeChrome(footerLeft, normalizedWatermark);
        footerRight = normalizeChrome(footerRight, "第 \\thepage 页 / 共 \\pageref{LastPage} 页");
    }

    /** Compatibility bridge for snapshots and services written before independent page chrome was persisted. */
    public TeachingTaskResponse(
            String taskId, String clientRequestId, String tenantId, String subjectType, String subjectId,
            TeachingHandoutTemplateResponse selectedTemplate, TeachingTaskStatus status, String questionText,
            String learningGoal, String watermarkText, List<TeachingWorkflowNode> nodes,
            List<TeachingWorkflowEvent> workflowEvents, List<TeachingReactStep> reactTrace,
            List<TeachingEvidence> evidence, String handoutLatex, String teacherHandoutLatex,
            String studentHandoutLatex, String lectureHandoutLatex, List<String> interactiveSuggestions,
            MemoryReuse memoryReuse, List<StageTiming> stageTimings, AiDraft aiDraft,
            TeachingDraftSections draftSections, TeachingDraftReview draftReview,
            TeachingDraftMergeResult mergeResult, String errorMessage) {
        this(taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status, questionText,
                learningGoal, watermarkText, nodes, workflowEvents, reactTrace, evidence, handoutLatex,
                teacherHandoutLatex, studentHandoutLatex, lectureHandoutLatex, interactiveSuggestions,
                memoryReuse, stageTimings, aiDraft, draftSections, draftReview, mergeResult, errorMessage,
                watermarkText, learningGoal, watermarkText, "第 \\thepage 页 / 共 \\pageref{LastPage} 页");
    }

    /**
     * Removes teacher explanations, answer-bearing evidence, ReAct details, and draft review fields for student
     * readers. The task owner still receives the student worksheet and durable progress status.
     */
    public TeachingTaskResponse studentSafe() {
        return new TeachingTaskResponse(
                taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                questionText, learningGoal, watermarkText, studentSafeNodes(nodes), studentSafeEvents(workflowEvents),
                List.of(), List.of(), "", "", studentHandoutLatex, "", interactiveSuggestions, memoryReuse,
                studentSafeTimings(stageTimings), null, null, null, null, "");
    }

    /**
     * Retains only execution state for student task polling. Workflow event summaries can contain source titles,
     * evidence excerpts, model metadata, or teacher-only diagnostics and must never cross the student boundary.
     */
    private static List<TeachingWorkflowEvent> studentSafeEvents(List<TeachingWorkflowEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .map(event -> new TeachingWorkflowEvent(
                        "", "", "system", "", "stage", event.status(), "", "", List.of()))
                .toList();
    }

    /** Nodes keep only their stable code and state because names and summaries can reveal source-bound work. */
    private static List<TeachingWorkflowNode> studentSafeNodes(List<TeachingWorkflowNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream().map(node -> new TeachingWorkflowNode(node.code(), "", node.status(), "")).toList();
    }

    /** Stage names can identify retrieval, rendering, or model work, so retain timing shape without its label. */
    private static List<StageTiming> studentSafeTimings(List<StageTiming> timings) {
        if (timings == null || timings.isEmpty()) {
            return List.of();
        }
        return timings.stream().map(timing -> new StageTiming("", timing.elapsedMs())).toList();
    }

    /** Creates a durable review transition without discarding the verified draft, evidence, or sibling versions. */
    public TeachingTaskResponse withReviewStatus(TeachingTaskStatus nextStatus, String nextErrorMessage) {
        return new TeachingTaskResponse(
                taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, nextStatus,
                questionText, learningGoal, watermarkText, nodes, workflowEvents, reactTrace, evidence,
                handoutLatex, teacherHandoutLatex, studentHandoutLatex, lectureHandoutLatex, interactiveSuggestions,
                memoryReuse, stageTimings, aiDraft, draftSections, draftReview, mergeResult, nextErrorMessage);
    }

    /**
     * Replaces the run-authorized evidence snapshot without altering ownership, execution state, or generated text.
     *
     * <p>The internal handout broker uses this narrow copy after a Python-selected teacher-resource query has passed
     * the same subject visibility check as the initial Java retrieval. Persisting that result makes its opaque
     * document reference available to a later bounded read and prevents the final task projection from reverting to
     * a pre-query zero-hit snapshot.</p>
     */
    public TeachingTaskResponse withEvidence(List<TeachingEvidence> nextEvidence) {
        return new TeachingTaskResponse(
                taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                questionText, learningGoal, watermarkText, nodes, workflowEvents, reactTrace,
                nextEvidence == null ? List.of() : List.copyOf(nextEvidence), handoutLatex, teacherHandoutLatex,
                studentHandoutLatex, lectureHandoutLatex, interactiveSuggestions, memoryReuse, stageTimings,
                aiDraft, draftSections, draftReview, mergeResult, errorMessage,
                headerLeft, headerRight, footerLeft, footerRight);
    }

    /**
     * Backward-compatible constructor for call sites that already set a selected template and lecture version
     * before workflow events were added.
     */
    public TeachingTaskResponse(
            String taskId,
            String clientRequestId,
            String tenantId,
            String subjectType,
            String subjectId,
            TeachingHandoutTemplateResponse selectedTemplate,
            TeachingTaskStatus status,
            String questionText,
            String learningGoal,
            List<TeachingWorkflowNode> nodes,
            List<TeachingReactStep> reactTrace,
            List<TeachingEvidence> evidence,
            String handoutLatex,
            String teacherHandoutLatex,
            String studentHandoutLatex,
            String lectureHandoutLatex,
            List<String> interactiveSuggestions,
            MemoryReuse memoryReuse,
            List<StageTiming> stageTimings,
            AiDraft aiDraft,
            String errorMessage) {
        this(
                taskId,
                clientRequestId,
                tenantId,
                subjectType,
                subjectId,
                selectedTemplate,
                status,
                questionText,
                learningGoal,
                "数学讲义",
                nodes,
                List.of(),
                reactTrace,
                evidence,
                handoutLatex,
                teacherHandoutLatex,
                studentHandoutLatex,
                lectureHandoutLatex,
                interactiveSuggestions,
                memoryReuse,
                stageTimings,
                aiDraft,
                null,
                null,
                null,
                errorMessage);
    }

    /**
     * Backward-compatible constructor for call sites that already set a selected template but do not
     * yet provide the independent lecture/slides handout version.
     */
    public TeachingTaskResponse(
            String taskId,
            String clientRequestId,
            String tenantId,
            String subjectType,
            String subjectId,
            TeachingHandoutTemplateResponse selectedTemplate,
            TeachingTaskStatus status,
            String questionText,
            String learningGoal,
            List<TeachingWorkflowNode> nodes,
            List<TeachingReactStep> reactTrace,
            List<TeachingEvidence> evidence,
            String handoutLatex,
            String teacherHandoutLatex,
            String studentHandoutLatex,
            List<String> interactiveSuggestions,
            MemoryReuse memoryReuse,
            List<StageTiming> stageTimings,
            AiDraft aiDraft,
            String errorMessage) {
        this(
                taskId,
                clientRequestId,
                tenantId,
                subjectType,
                subjectId,
                selectedTemplate,
                status,
                questionText,
                learningGoal,
                "数学讲义",
                nodes,
                List.of(),
                reactTrace,
                evidence,
                handoutLatex,
                teacherHandoutLatex,
                studentHandoutLatex,
                "",
                interactiveSuggestions,
                memoryReuse,
                stageTimings,
                aiDraft,
                null,
                null,
                null,
                errorMessage);
    }

    /**
     * Backward-compatible constructor for older call sites that do not set a selected template.
     */
    public TeachingTaskResponse(
            String taskId,
            String clientRequestId,
            String tenantId,
            String subjectType,
            String subjectId,
            TeachingTaskStatus status,
            String questionText,
            String learningGoal,
            List<TeachingWorkflowNode> nodes,
            List<TeachingReactStep> reactTrace,
            List<TeachingEvidence> evidence,
            String handoutLatex,
            String teacherHandoutLatex,
            String studentHandoutLatex,
            List<String> interactiveSuggestions,
            MemoryReuse memoryReuse,
            List<StageTiming> stageTimings,
            AiDraft aiDraft,
            String errorMessage) {
        this(
                taskId,
                clientRequestId,
                tenantId,
                subjectType,
                subjectId,
                null,
                status,
                questionText,
                learningGoal,
                "数学讲义",
                nodes,
                List.of(),
                reactTrace,
                evidence,
                handoutLatex,
                teacherHandoutLatex,
                studentHandoutLatex,
                "",
                interactiveSuggestions,
                memoryReuse,
                stageTimings,
                aiDraft,
                null,
                null,
                null,
                errorMessage);
    }

    /**
     * Returns the requested LaTeX handout version, using the teacher version as the default for compatibility.
     *
     * @param version version code from backend-controlled routes, such as teacher or student
     * @return LaTeX source for the requested handout version
     */
    public String handoutLatexFor(String version) {
        if ("lecture".equalsIgnoreCase(version)) {
            return lectureHandoutLatex == null || lectureHandoutLatex.isBlank() ? handoutLatexFor("teacher") : lectureHandoutLatex;
        }
        if ("student".equalsIgnoreCase(version)) {
            return studentHandoutLatex == null || studentHandoutLatex.isBlank() ? handoutLatex : studentHandoutLatex;
        }
        return teacherHandoutLatex == null || teacherHandoutLatex.isBlank() ? handoutLatex : teacherHandoutLatex;
    }

    /** Returns a transport-safe snapshot with only the persisted PDF attribution changed. */
    public TeachingTaskResponse withWatermarkText(String value) {
        return new TeachingTaskResponse(
                taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                questionText, learningGoal, value, nodes, workflowEvents, reactTrace, evidence, handoutLatex,
                teacherHandoutLatex, studentHandoutLatex, lectureHandoutLatex, interactiveSuggestions, memoryReuse,
                stageTimings, aiDraft, draftSections, draftReview, mergeResult, errorMessage);
    }

    /** Replaces only the persisted page chrome while retaining every workflow and handout field. */
    public TeachingTaskResponse withPageChrome(String leftHeader, String rightHeader, String leftFooter, String rightFooter) {
        return new TeachingTaskResponse(
                taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                questionText, learningGoal, watermarkText, nodes, workflowEvents, reactTrace, evidence,
                handoutLatex, teacherHandoutLatex, studentHandoutLatex, lectureHandoutLatex,
                interactiveSuggestions, memoryReuse, stageTimings, aiDraft, draftSections, draftReview,
                mergeResult, errorMessage, leftHeader, rightHeader, leftFooter, rightFooter);
    }

    /** Normalizes display-only attribution while preserving a teacher's own custom label. */
    private static String normalizeWatermarkText(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() || "飞猪数学".equals(normalized) ? "数学讲义" : normalized;
    }

    /** Removes control characters from user-visible page labels before they enter the LaTeX source. */
    private static String normalizeChrome(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
        return normalized.isBlank() ? fallback : normalized;
    }

    /**
     * Produces a copy with exactly one persisted handout version replaced. The compatibility `handoutLatex` field
     * continues to mirror the teacher version so older exporters and historical clients see the edited teacher draft.
     *
     * @param version teacher, student, or lecture
     * @param latex already validated LaTeX source
     * @return task snapshot with all workflow evidence, timings, and other versions preserved
     */
    public TeachingTaskResponse withHandoutVersion(String version, String latex) {
        String normalized = version == null ? "teacher" : version.strip().toLowerCase(java.util.Locale.ROOT);
        String value = latex == null ? "" : latex;
        return switch (normalized) {
            case "student" -> new TeachingTaskResponse(
                    taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                    questionText, learningGoal, watermarkText, nodes, workflowEvents, reactTrace, evidence, handoutLatex,
                    teacherHandoutLatex, value, lectureHandoutLatex, interactiveSuggestions, memoryReuse,
                    stageTimings, aiDraft, draftSections, draftReview, mergeResult, errorMessage);
            case "lecture" -> new TeachingTaskResponse(
                    taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                    questionText, learningGoal, watermarkText, nodes, workflowEvents, reactTrace, evidence, handoutLatex,
                    teacherHandoutLatex, studentHandoutLatex, value, interactiveSuggestions, memoryReuse,
                    stageTimings, aiDraft, draftSections, draftReview, mergeResult, errorMessage);
            case "teacher" -> new TeachingTaskResponse(
                    taskId, clientRequestId, tenantId, subjectType, subjectId, selectedTemplate, status,
                    questionText, learningGoal, watermarkText, nodes, workflowEvents, reactTrace, evidence, value,
                    value, studentHandoutLatex, lectureHandoutLatex, interactiveSuggestions, memoryReuse,
                    stageTimings, aiDraft, draftSections, draftReview, mergeResult, errorMessage);
            default -> throw new IllegalArgumentException("Unsupported handout version: " + version);
        };
    }

    /**
     * 学生记忆复用摘要。
     *
     * @param reused 是否复用了历史答案。
     * @param memoryId 命中的记忆 ID；未命中时为空。
     * @param reuseScope private 表示学生私有，public 表示租户内公开复用。
     * @param answer 可复用答案文本；未命中时为空。
     * @param similarity 相似度分数，范围 0 到 1。
     * @param reason 复用或不复用的原因，便于审计和前端显示。
     */
    public record MemoryReuse(
            boolean reused,
            String memoryId,
            String reuseScope,
            String answer,
            double similarity,
            String reason) {
    }

    /**
     * 教学 DAG 阶段耗时。
     *
     * @param stage 阶段编码，例如 memory_reuse、textbook_retrieval。
     * @param elapsedMs 当前阶段耗时毫秒数。
     */
    public record StageTiming(String stage, long elapsedMs) {
    }

    /**
     * Real AI draft metadata and content used by the teaching workflow.
     *
     * @param enabled whether a model call was attempted
     * @param providerName provider that answered
     * @param modelCode model that answered
     * @param promptTokens provider-reported prompt tokens
     * @param completionTokens provider-reported completion tokens
     * @param totalTokens provider-reported total tokens
     * @param content model-generated classroom content
     * @param message safe model status message
     * @param structured whether content was parsed into the expected JSON schema
     * @param teacherExplanation teacher-facing explanation parsed from model JSON
     * @param studentHint student-facing hint parsed from model JSON
     * @param knowledgePoints knowledge points parsed from model JSON
     * @param followUpQuestions follow-up questions parsed from model JSON
     * @param parseError parser error when structured is false
     * @param retryCount actual retry count used by the AI draft stage
     * @param maxRetries backend retry limit for this stage
     * @param recoveredAfterRetry whether structured output succeeded after at least one retry
     * @param recoveryEvents structured recovery events for model calls, JSON parsing, retries, and provider rotation
     */
    public record AiDraft(
            boolean enabled,
            String providerName,
            String modelCode,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String content,
            String message,
            boolean structured,
            String teacherExplanation,
            String studentHint,
            String lectureContent,
            List<String> knowledgePoints,
            List<String> followUpQuestions,
            String parseError,
            int retryCount,
            int maxRetries,
            boolean recoveredAfterRetry,
            List<AiRecoveryEvent> recoveryEvents,
            List<AssetPlacement> assetPlacements) {

        /** Compatibility constructor for existing durable drafts created before asset placement metadata existed. */
        public AiDraft(
                boolean enabled,
                String providerName,
                String modelCode,
                int promptTokens,
                int completionTokens,
                int totalTokens,
                String content,
                String message,
                boolean structured,
                String teacherExplanation,
                String studentHint,
                String lectureContent,
                List<String> knowledgePoints,
                List<String> followUpQuestions,
                String parseError,
                int retryCount,
                int maxRetries,
                boolean recoveredAfterRetry,
                List<AiRecoveryEvent> recoveryEvents) {
            this(enabled, providerName, modelCode, promptTokens, completionTokens, totalTokens, content, message,
                    structured, teacherExplanation, studentHint, lectureContent, knowledgePoints, followUpQuestions,
                    parseError, retryCount, maxRetries, recoveredAfterRetry, recoveryEvents, List.of());
        }

        /**
         * Backward-compatible constructor for disabled and provider-failure responses.
         */
        public AiDraft(
                boolean enabled,
                String providerName,
                String modelCode,
                int promptTokens,
                int completionTokens,
                int totalTokens,
                String content,
                String message) {
            this(enabled, providerName, modelCode, promptTokens, completionTokens, totalTokens, content, message,
                    false, "", "", "", List.of(), List.of(), "", 0, 0, false, List.of(), List.of());
        }
    }

    /**
     * AI-authored image placement contract. Asset identifiers remain opaque until Java revalidates task, subject and
     * variant ownership before controlled materialization for the renderer.
     */
    public record AssetPlacement(
            int questionNumber,
            List<String> assetIds,
            String anchor,
            String layout,
            List<String> variants,
            String caption) {
    }

    /**
     * One backend-owned AI recovery event. Events intentionally avoid raw prompt and raw model content.
     *
     * @param eventType stable event code, such as MODEL_CALL_SUCCEEDED or JSON_PARSE_FAILED
     * @param providerName provider attempted by the backend
     * @param modelCode model attempted by the backend
     * @param attemptNo zero-based attempt number within the provider
     * @param structured whether this event observed a valid structured draft
     * @param retryable whether the backend still had a retry or provider fallback path after the event
     * @param message short safe message for UI and audit
     */
    public record AiRecoveryEvent(
            String eventType,
            String providerName,
            String modelCode,
            int attemptNo,
            boolean structured,
            boolean retryable,
            String message) {
    }
}
