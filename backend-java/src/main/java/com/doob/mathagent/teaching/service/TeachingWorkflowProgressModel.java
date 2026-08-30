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
import java.util.concurrent.CompletionException;
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
 * TeachingWorkflowProgressModel owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates progressmodel rules.
 */
final class TeachingWorkflowProgressModel {
    private TeachingWorkflowProgressModel() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    /** Builds user-visible statuses without exposing model prompts, provider diagnostics, or raw source payloads. */
    static List<TeachingWorkflowNode> progressWorkflowNodes(
            TeachingTaskRequest request,
            StudentMemoryResponse memoryResponse,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template,
            boolean questionBankAllowed,
            boolean teacherResourceAllowed,
            ProgressPhase phase,
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome) {
        boolean evidenceReady = phase != ProgressPhase.EVIDENCE_COLLECTING;
        boolean outlineReady = phase == ProgressPhase.CONTENT_GENERATING || phase == ProgressPhase.HANDOUT_RENDERING;
        boolean contentGenerating = phase == ProgressPhase.CONTENT_GENERATING;
        boolean draftReady = aiDraft != null;
        boolean reused = memoryResponse.reused();
        long publicCount = textbookEvidence == null ? 0L : textbookEvidence.stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope())).count();
        List<TeachingWorkflowNode> nodes = new ArrayList<>(List.of(
                node("LEARNING_GOAL", "学习目标识别", "completed", "已确认学习目标：" + request.learningGoal()),
                // REUSE_RESOURCE 阶段已删除：老板不需要这个功能
                retrievalNode("PUBLIC_TEXTBOOK_RETRIEVAL", "公开教材检索", publicCount,
                        textbookOutcome, "公开教材证据", "正在并行检索公开教材。"),
                !questionBankAllowed
                        ? node("QUESTION_BANK_RETRIEVAL", "题库检索", "skipped", "当前身份没有题库读取权限。")
                        : retrievalNode("QUESTION_BANK_RETRIEVAL", "题库检索", questionEvidence.size(),
                                questionOutcome, "题库题目", "等待课程点确定后检索题库。"),
                !teacherResourceAllowed
                        ? node("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索", "skipped", "当前身份没有教师资料读取权限。")
                        : retrievalNode("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索", teacherResourceEvidence.size(),
                                teacherResourceOutcome, "教师资料证据", "正在并行检索已同步教师资料。"),
                node("REACT_SOLVE", "讲解大纲", outlineReady ? "completed" : evidenceReady ? "running" : "pending",
                        outlineReady ? "已按汇总证据确定讲解大纲。" : evidenceReady ? "正在把来源汇总为讲解大纲。" : "等待资料汇总。"),
                node("HANDOUT_TEMPLATE", "讲义结构", "completed", "已确定讲义结构。"),
                node("AI_DRAFT", "讲义内容生成", draftReady ? "completed" : contentGenerating ? "running" : "pending",
                        draftReady ? aiDraftSummary(aiDraft) : contentGenerating ? "正在按讲解大纲生成结构化内容。" : "等待讲解大纲。"),
                node("LATEX_HANDOUT", "讲义排版", draftReady ? "running" : "pending",
                        draftReady ? "正在生成教师版、学生版和 16:10 讲解版。" : "等待结构化内容。"),
                node("HUMAN_FEEDBACK", "人类反馈", "pending", "三个版本完成后可提交审校反馈。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "pending", "三个版本完成后提供后续练习建议。")));
        nodes.addAll(questionAgentNodes(questionEvidence, evidenceReady, outlineReady));
        return List.copyOf(nodes);
    }


    /** Presents a settled source branch without turning a timeout into a false successful empty search. */
    private static TeachingWorkflowNode retrievalNode(
            String code,
            String name,
            long evidenceCount,
            RetrievalOutcome outcome,
            String evidenceLabel,
            String runningDetail) {
        RetrievalOutcome resolved = outcome == null ? RetrievalOutcome.running() : outcome;
        String detail = switch (resolved.status()) {
            case "completed" -> "命中" + evidenceLabel + " " + evidenceCount + " 条。";
            case "degraded", "failed", "skipped" -> resolved.detail();
            default -> runningDetail;
        };
        return node(code, name, resolved.status(), detail);
    }


    /** Builds the safe event hierarchy displayed while the fixed DAG is still executing. */
    static List<TeachingWorkflowEvent> progressWorkflowEvents(
            TeachingHandoutTemplateProfile template,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            ProgressPhase phase,
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome) {
        boolean evidenceReady = phase != ProgressPhase.EVIDENCE_COLLECTING;
        boolean outlineReady = phase == ProgressPhase.CONTENT_GENERATING || phase == ProgressPhase.HANDOUT_RENDERING;
        boolean contentGenerating = phase == ProgressPhase.CONTENT_GENERATING;
        boolean draftReady = aiDraft != null;
        List<TeachingWorkflowEvent> events = new ArrayList<>(List.of(
                workflowEvent("plan", "system", "TeachingPlanner", "plan", "教学任务计划", "已确定讲义结构。", List.of()),
                workflowEvent("evidence", "tool", "EvidenceCollector", "evidence", "并行收集教材、题库和教师资料证据",
                        evidenceProgressDetail(textbookEvidence, questionEvidence, teacherResourceEvidence,
                                textbookOutcome, questionOutcome, teacherResourceOutcome),
                        evidenceProgressStatus(textbookOutcome, questionOutcome, teacherResourceOutcome, evidenceReady),
                        List.of("PUBLIC_TEXTBOOK", "QUESTION_BANK", "TEACHER_RESOURCE")),
                workflowEvent("outline", "agent", "OutlinePlanner", "outline", "生成讲解大纲",
                        outlineReady ? "已根据汇总来源确定讲解大纲。" : evidenceReady ? "正在将来源整理为讲解大纲。" : "等待资料汇总。",
                        outlineReady ? "completed" : evidenceReady ? "running" : "pending", List.of()),
                workflowEvent("generation", "agent", "CoursewareAgent", "generation", "生成三个版本内容",
                        draftReady ? aiDraftSummary(aiDraft) : contentGenerating ? "正在生成讲义的结构化内容。" : "等待讲解大纲。",
                        draftReady ? "completed" : contentGenerating ? "running" : "pending", List.of("teacher", "student", "lecture")),
                workflowEvent("render", "system", "HandoutRenderer", "render", "生成多版本讲义产物",
                        draftReady ? "正在渲染教师版、学生版和 16:10 讲解版。" : "等待结构化内容。",
                        draftReady ? "running" : "pending", List.of("teacher", "student", "lecture"))));
        // Final snapshots are produced only after every question branch has returned. The progress snapshot above
        // still reports running children; this completed snapshot reflects the real execution barrier.
        events.addAll(questionAgentEvents(questionEvidence, "completed"));
        return List.copyOf(events);
    }


    /** Keeps the aggregate event honest while source branches settle at different times. */
    private static String evidenceProgressDetail(
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome) {
        List<String> degraded = List.of(textbookOutcome, questionOutcome, teacherResourceOutcome).stream()
                .filter(outcome -> outcome != null && ("degraded".equals(outcome.status())
                        || "failed".equals(outcome.status()) || "skipped".equals(outcome.status())))
                .map(RetrievalOutcome::detail)
                .filter(detail -> detail != null && !detail.isBlank())
                .toList();
        String evidence = evidenceWorkflowDetail(textbookEvidence, questionEvidence, teacherResourceEvidence);
        if (degraded.isEmpty()) {
            return evidence;
        }
        return String.join("；", degraded) + "\n" + evidence;
    }

    private static String evidenceProgressStatus(
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome,
            boolean evidenceReady) {
        boolean running = List.of(textbookOutcome, questionOutcome, teacherResourceOutcome).stream()
                .anyMatch(outcome -> outcome == null || "running".equals(outcome.status()));
        boolean degraded = List.of(textbookOutcome, questionOutcome, teacherResourceOutcome).stream()
                .anyMatch(outcome -> outcome != null && ("degraded".equals(outcome.status())
                        || "failed".equals(outcome.status())));
        return running ? "running" : degraded ? "degraded" : evidenceReady ? "completed" : "running";
    }


    /**
     * Builds the durable, user-readable retrieval record for SSE and refresh recovery.  Every item in the owned
     * evidence snapshot is named here instead of reducing the result to a count; raw source access remains governed
     * by the existing document/block permission checks in the inspector endpoint.
     */
    static String evidenceWorkflowDetail(
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence) {
        List<TeachingEvidence> allEvidence = new ArrayList<>();
        allEvidence.addAll(textbookEvidence == null ? List.of() : textbookEvidence);
        allEvidence.addAll(questionEvidence == null ? List.of() : questionEvidence);
        allEvidence.addAll(teacherResourceEvidence == null ? List.of() : teacherResourceEvidence);
        if (allEvidence.isEmpty()) {
            return "本轮未命中可用资料。下一步：按学习目标生成基础讲义结构，并提示继续补充原题或资料。";
        }
        StringBuilder detail = new StringBuilder("已找到 ").append(allEvidence.size()).append(" 条已授权内容：");
        int index = 1;
        for (TeachingEvidence evidence : allEvidence) {
            detail.append('\n').append(index).append(". ")
                    .append(evidenceDisplayName(evidence));
            String snippet = normalizedInlineText(evidence.snippet());
            if (!snippet.isBlank()) {
                detail.append("：").append(snippet);
            }
            index += 1;
        }
        detail.append("\n下一步：以这些来源逐题核对知识点、题干与答案，再组织讲解大纲和三个讲义版本。");
        return detail.toString();
    }


    /** Gives every retrieval line a stable, human-readable file/question and page reference. */
    static String evidenceDisplayName(TeachingEvidence evidence) {
        String scope = switch (evidence.sourceScope()) {
            case "PUBLIC_TEXTBOOK" -> "公开教材";
            case "QUESTION_BANK" -> "题库题目";
            case "TEACHER_RESOURCE" -> "教师资料";
            default -> evidence.sourceScope() == null ? "资料" : evidence.sourceScope();
        };
        String title = printableEvidenceTitle(evidence.sourceTitle());
        String page = evidence.pageNo() > 0 ? "第 " + evidence.pageNo() + " 页" : "页码未记录";
        return scope + "《" + title + "》(" + page + ")";
    }


    /** Child events expose one isolated question-agent branch below the aggregate generation event. */

    static List<TeachingWorkflowEvent> questionAgentEvents(
            List<TeachingEvidence> questionEvidence,
            String status) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return List.of();
        }
        return questionEvidence.stream()
                .map(evidence -> {
                    String id = questionAgentId(evidence);
                    return childWorkflowEvent(
                            "question-agent-" + id,
                            "generation",
                            "agent",
                            "QuestionAgent-" + id,
                            "question_agent",
                            "题目独立编排",
                            "本题使用隔离上下文完成证据对齐，未共享其他题目的内容。",
                            status,
                            List.of(evidenceRef(evidence)));
                })
                .toList();
    }


    static TeachingWorkflowEvent childWorkflowEvent(
            String eventId,
            String parentEventId,
            String sourceType,
            String sourceName,
            String eventType,
            String title,
            String summary,
            String status,
            List<String> artifactRefs) {
        return new TeachingWorkflowEvent(
                eventId,
                parentEventId,
                sourceType,
                sourceName,
                eventType,
                status,
                title,
                summary,
                artifactRefs == null ? List.of() : List.copyOf(artifactRefs));
    }


    /**
     * 构造真实执行过的 DAG 节点输出；未执行的扩展能力不得伪装为 completed。
     */
    static List<TeachingWorkflowNode> buildNodes(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            StudentMemoryResponse memoryResponse,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template,
            boolean questionBankAllowed,
            boolean teacherResourceAllowed,
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome) {
        // REUSE_RESOURCE 阶段已删除：老板不需要这个功能
        // Memory reuse supplies context only; evidence retrieval still runs so every published handout has current,
        // expandable sources. A reused answer must never make the DAG claim that retrieval was skipped.
        long publicTextbookCount = evidence.stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope()))
                .count();
        long teacherResourceCount = teacherResourceEvidence.size();
        List<TeachingWorkflowNode> nodes = new ArrayList<>(List.of(
                node("LEARNING_GOAL", "学习目标识别", "识别用户想学：" + request.learningGoal()),
                // REUSE_RESOURCE 节点已删除
                retrievalNode("PUBLIC_TEXTBOOK_RETRIEVAL", "公开教材检索", publicTextbookCount,
                        textbookOutcome, "公开教材证据", "公开教材检索未完成。"),
                !questionBankAllowed
                        ? node("QUESTION_BANK_RETRIEVAL", "题库检索", "skipped", "当前身份没有题库读取权限。")
                        : retrievalNode("QUESTION_BANK_RETRIEVAL", "题库检索", questionEvidence.size(),
                                questionOutcome, "题库题目", "题库检索未完成。"),
                !teacherResourceAllowed
                        ? node("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索", "skipped", "当前身份没有教师资料读取权限。")
                        : retrievalNode("TEACHER_RESOURCE_RETRIEVAL", "教师资料检索", teacherResourceCount,
                                teacherResourceOutcome, "教师资料证据", "教师资料检索未完成。"),
                node("REACT_SOLVE", "解题编排", "基于教材证据、学生记忆和题型方法整理讲解步骤。"),
                node("HANDOUT_TEMPLATE", "讲义结构", "自动组织讲义结构。"),
                node("AI_DRAFT", "讲义内容生成", aiDraftSummary(aiDraft)),
                node("LATEX_HANDOUT", "讲义排版", "生成教师版、学生版和横版讲解稿，可预览并导出 PDF。"),
                node("HUMAN_FEEDBACK", "人类反馈", "pending", "等待学生或教师提交人工反馈。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "给出继续追问、练习和导出建议。")));
        nodes.addAll(questionAgentNodes(questionEvidence, true, true));
        return List.copyOf(nodes);
    }


    /** Creates a stable fan-out node for each verified question without exposing raw model prompts. */
    static List<TeachingWorkflowNode> questionAgentNodes(
            List<TeachingEvidence> questionEvidence,
            boolean evidenceReady,
            boolean outlineReady) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return List.of();
        }
        return questionEvidence.parallelStream()
                .map(evidence -> {
                    String id = questionAgentId(evidence);
                    String title = evidence.sourceTitle() == null || evidence.sourceTitle().isBlank()
                            ? "题目独立智能体"
                            : evidence.sourceTitle().split(" / ", 2)[0];
                     return node("QUESTION_AGENT_" + id, "题目 " + title,
                             "completed",
                             "题目子智能体已完成独立模型调用，并仅使用本题检索证据。");
                })
                .sorted(Comparator.comparing(TeachingWorkflowNode::code))
                .toList();
    }


    static String questionAgentId(TeachingEvidence evidence) {
        String raw = evidence == null ? "" : evidence.chunkId();
        if (raw == null || raw.isBlank()) {
            raw = evidence == null ? "question" : evidence.sourceTitle();
        }
        String normalized = raw == null ? "QUESTION"
                : raw.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "QUESTION";
        }
        return normalized + "_" + Integer.toUnsignedString(raw == null ? 0 : raw.hashCode(), 16);

    }


    /** Fans out immutable question contexts so future per-question agents cannot share mutable state. */
    static QuestionAgentBatch prepareQuestionAgentContexts(List<TeachingEvidence> questionEvidence) {
        if (questionEvidence == null || questionEvidence.isEmpty()) {
            return new QuestionAgentBatch(0, 0L, List.of());
        }
        long started = System.nanoTime();
        // Context construction is pure allocation and does not perform model or I/O work. Creating a private pool
        // here only to map a few records multiplied thread count per request; real question-agent calls are fanned
        // out later through the bounded workflow executor in TeachingWorkflowExecutionSupport.
        List<QuestionAgentBranch> branches = questionEvidence.stream()
                .map(evidence -> {
                    QuestionAgentContext context = new QuestionAgentContext(
                            questionAgentId(evidence), evidence.sourceTitle(), List.of(evidence));
                    return new QuestionAgentBranch(context, 0L);
                })
                .sorted(Comparator.comparing(branch -> branch.context().agentId()))
                .toList();
        return new QuestionAgentBatch(branches.size(), Math.max(0L,
                (System.nanoTime() - started) / 1_000_000L),
                branches.stream().map(branch -> new QuestionAgentTiming(
                        branch.context().agentId(), branch.elapsedMs())).toList());
    }


    /**
     * Summarizes the real AI draft result for the DAG node without exposing raw model content.
     */
    static String aiDraftSummary(TeachingTaskResponse.AiDraft aiDraft) {
        if (aiDraft == null || !aiDraft.enabled()) {
            return "未生成模型内容，讲义仅使用教材、题库和模板内容。";
        }
        if (aiDraft.structured()) {
            return "讲义草稿已整理为教师讲解、学生提示、知识点和追问任务，可进入人工审校。";
        }
        return "讲义草稿未能稳定结构化，请先人工复核内容后再导出使用。";
    }


    /**
     * 创建已完成 DAG 节点。
     */
    static TeachingWorkflowNode node(String code, String name, String summary) {
        return new TeachingWorkflowNode(code, name, "completed", summary);
    }


    static TeachingWorkflowNode node(String code, String name, String status, String summary) {
        return new TeachingWorkflowNode(code, name, status, summary);
    }

    /**
     * 终态失败落盘时收敛运行中的 DAG 节点：running → failed。
     *
     * <p>失败快照若原样保留生成中节点，前端会永远显示"生成中"且与任务徽章（失败）互相矛盾；
     * 未开始的 pending 节点保持原状，因为它们确实从未执行。</p>
     */
    static List<TeachingWorkflowNode> failRunningNodes(List<TeachingWorkflowNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return nodes == null ? List.of() : nodes;
        }
        return nodes.stream()
                .map(node -> "running".equals(node.status())
                        ? new TeachingWorkflowNode(node.code(), node.name(), "failed", "本次生成已失败终止。")
                        : node)
                .toList();
    }


    /**
     * Builds a recoverable event snapshot from the completed workflow without exposing raw prompts or diagnostics.
     */
    static List<TeachingWorkflowEvent> buildWorkflowEvents(
            List<TeachingWorkflowNode> nodes,
            List<TeachingEvidence> evidence,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> questionEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingHandoutTemplateProfile template,
            RetrievalOutcome textbookOutcome,
            RetrievalOutcome questionOutcome,
            RetrievalOutcome teacherResourceOutcome) {
        List<String> evidenceScopes = evidence.stream()
                .map(TeachingEvidence::sourceScope)
                .filter(scope -> scope != null && !scope.isBlank())
                .distinct()
                .toList();
        List<TeachingWorkflowEvent> events = new ArrayList<>(List.of(
                workflowEvent(
                        "plan",

                        "system",
                        "TeachingPlanner",
                        "plan",
                        "教学任务计划",
                        "生成讲义任务流程，自动组织结构。",
                        List.of(template.summary().templateCode())),
                workflowEvent(
                        "evidence",
                        "tool",
                        "EvidenceCollector",
                        "evidence",
                        "并行收集教材、题库和教师资料证据",
                        evidenceProgressDetail(textbookEvidence, questionEvidence, teacherResourceEvidence,
                                textbookOutcome, questionOutcome, teacherResourceOutcome),
                        evidenceProgressStatus(textbookOutcome, questionOutcome, teacherResourceOutcome, true),
                        evidenceScopes),
                workflowEvent(
                        "generation",
                        "agent",
                        "CoursewareAgent",
                        "generation",
                        "生成讲义草稿",
                        aiDraftSummary(aiDraft),
                        aiDraft != null && aiDraft.enabled() ? List.of("AI_DRAFT") : List.of("TEMPLATE_DRAFT")),
                workflowEvent(
                        "render",
                        "system",
                        "HandoutRenderer",
                        "render",
                        "生成多版本讲义产物",
                        "生成 teacher、student、lecture 三个 LaTeX 版本，PDF 渲染由导出服务继续处理。",
                        List.of("teacher", "student", "lecture")),
                workflowEvent(
                        "review",
                        "reviewer",
                        "HumanFeedback",
                        "review",
                        "等待人工审校",
                        nodeSummary(nodes, "HUMAN_FEEDBACK"),
                        List.of())));
        // Child contexts are not independent model executions yet; do not mark them completed in the final snapshot.
        events.addAll(questionAgentEvents(questionEvidence, "running"));
        return List.copyOf(events);
    }


    static TeachingWorkflowEvent workflowEvent(
            String eventId,
            String sourceType,
            String sourceName,
            String eventType,
            String title,
            String summary,
            List<String> artifactRefs) {
        return workflowEvent(eventId, sourceType, sourceName, eventType, title, summary, "completed", artifactRefs);
    }


    /** Creates an event with an explicit durable running/completed/pending status for SSE snapshots. */
    static TeachingWorkflowEvent workflowEvent(
            String eventId,
            String sourceType,
            String sourceName,
            String eventType,
            String title,
            String summary,
            String status,
            List<String> artifactRefs) {
        return new TeachingWorkflowEvent(
                eventId,
                "",
                sourceType,
                sourceName,
                eventType,
                status,
                title,
                summary,
                artifactRefs == null ? List.of() : List.copyOf(artifactRefs));
    }


    static String nodeSummary(List<TeachingWorkflowNode> nodes, String code) {
        return nodes.stream()
                .filter(node -> code.equals(node.code()))
                .map(TeachingWorkflowNode::summary)
                .findFirst()
                .orElse("");
    }
}
