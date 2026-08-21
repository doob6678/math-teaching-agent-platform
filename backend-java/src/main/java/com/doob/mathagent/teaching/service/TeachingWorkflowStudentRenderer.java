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
 * TeachingWorkflowStudentRenderer owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates studentrenderer rules.
 */
final class TeachingWorkflowStudentRenderer {
    private TeachingWorkflowStudentRenderer() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    static String evidenceLabel(TeachingEvidence item) {
        if ("QUESTION_BANK".equals(item.sourceScope())) {
            return "题库：" + questionTitleWithoutDifficulty(item);
        }
        if ("TEACHER_RESOURCE".equals(item.sourceScope())) {
            return item.pageNo() > 0
                    ? printableEvidenceTitle(item.sourceTitle()) + " / 第 " + item.pageNo() + " 页"
                    : printableEvidenceTitle(item.sourceTitle());
        }
        return printableEvidenceTitle(item.sourceTitle()) + " / PDF " + item.pageNo();
    }


    /** Keeps human-readable source names while hiding opaque ids from printable teacher/student content. */
    static String printableEvidenceTitle(String value) {
        if (value == null || value.isBlank()) {
            return "未命名资料";
        }
        return value.replaceAll("\\b[A-Za-z0-9]{24,}\\b", "")
                .replaceAll("\\s{2,}", " ")
                .strip();
    }


    /**
     * 生成学生版 LaTeX 讲义：保留题目、提示和干净空白，不直接暴露教师解析和知识点归属。
     */
    static String buildStudentHandoutLatex(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingKnowledgePointPack> knowledgePointPacks,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingDraftSections draftSections) {
        String hint = memoryResponse.reused()
                ? "回忆同类问题的方法，先写出已知条件，再判断可用公式。"
                : evidence.isEmpty()
                ? "先圈出题目中的关键词，再尝试写出相关定义。"
                : "先阅读教材证据中的定义或公式，再补全自己的推理。";
        String lectureTitle = studentLectureTitle(request);
        String studentHint = draftSections == null ? "" : draftSections.studentWorksheet();
        String draftKnowledge = draftBlockContent(studentHint, studentDraftLabels(), "知识速记");
        String draftType = draftBlockContent(studentHint, studentDraftLabels(), "题型识别");
        String draftExample = draftBlockContent(studentHint, studentDraftLabels(), "例题任务");
        String draftPractice = draftBlockContent(studentHint, studentDraftLabels(), "练习任务");
        String draftNotice = draftBlockContent(studentHint, studentDraftLabels(), "作答提醒");
        String knowledgeSection = studentKnowledgeCardsLatex(mergeDistinctItems(
                5,
                draftBlockLines(draftKnowledge),
                guardDraftItems(aiDraft == null ? List.of() : aiDraft.knowledgePoints(), false),
                studentKnowledgeCards(request, evidence, hint)));
        String noteSection = latexItemize(mergeDistinctItems(
                5,
                draftBlockLines(draftNotice),
                studentNoticeCards(request, evidence)));
        String methodSection = latexEnumerate(mergeDistinctItems(
                5,
                draftBlockLines(draftType),
                studentMethodCards(request, evidence)));
        String exampleSection = contentOrFallback(draftExample, studentExampleSection(request, evidence, hint));
        List<String> practiceItems = draftSections == null
                ? studentPracticeTasks(request, evidence, aiDraft, draftPractice)
                : draftSections.exercises();
        int blankSpaceEm = template.blankSpaceEm();
        String evidenceImage = evidence.stream()
                .map(TeachingEvidence::imagePath)
                .filter(path -> path != null && !path.isBlank())
                .findFirst()
                .map(TeachingWorkflowService::authorizedImageLatex)
                .orElse("");
        if (knowledgePointPacks != null && !knowledgePointPacks.isEmpty()) {
            // A global AI worksheet is unsafe for several sibling knowledge points, but is concrete
            // and valuable when exactly one verified point owns the page. Preserve its student-safe
            // facts, recognition signals, and tasks instead of replacing them with generic filler.
            boolean oneKnowledgePoint = knowledgePointPacks.size() == 1;
            return buildStudentKnowledgePointHandout(
                    lectureTitle,
                    knowledgePointPacks,
                    blankSpaceEm,
                    oneKnowledgePoint ? draftBlockLines(draftKnowledge) : List.of(),
                    oneKnowledgePoint ? draftBlockLines(draftType) : List.of(),
                    oneKnowledgePoint ? draftBlockLines(draftPractice) : List.of());
        }
        if (template.studentLectureStyle()) {
            return """
                    \\section{%s}
                    %s

                    \\section{知识速记}
                    %s

                    \\section{注意}
                    %s

                    \\section{题型识别}
                    %s

                    \\section{典型例题}
                    %s
                    %s

                    %s

                    \\section{订正与错因}
                    \\vspace{6em}
                    """.formatted(
                    escapeLatex(lectureTitle),
                    evidenceImage,
                    knowledgeSection,
                    noteSection,
                    methodSection,
                    exampleSection,
                    studentQuestionPages("连续编号练习", practiceItems, blankSpaceEm),
                    studentQuestionBankSection(request, evidence, blankSpaceEm));
        }
        return """
                \\section{%s}
                %s

                \\section{知识速记}
                %s

                \\section{注意}
                %s

                \\section{题型识别}
                %s

                \\section{典型例题}

                %s
                %s

                %s

                \\section{错因整理}
                \\vspace{6em}
                """.formatted(
                escapeLatex(lectureTitle),
                evidenceImage,
                knowledgeSection,
                noteSection,
                methodSection,
                exampleSection,
                studentQuestionPages("连续编号练习", practiceItems, blankSpaceEm),
                studentQuestionBankSection(request, evidence, blankSpaceEm));
    }


    /**
     * Writes student material in the same knowledge-point order as the teacher version while withholding solutions.
     * Each real question gets its own page and writing area, so dense retrieval never collapses several examples into
     * a small generic exercise list.
     */
    static String buildStudentKnowledgePointHandout(
            String lectureTitle,
            List<TeachingKnowledgePointPack> packs,

            int configuredWorkspaceEm,
            List<String> aiKnowledgeNotes,
            List<String> aiRecognitionSignals,
            List<String> aiPracticeTasks) {
        int workspace = Math.max(STUDENT_QUESTION_WORKSPACE_EM, configuredWorkspaceEm);
        List<String> safeKnowledgeNotes = guardDraftItems(aiKnowledgeNotes, false);
        List<String> safeRecognitionSignals = guardDraftItems(aiRecognitionSignals, false);
        List<String> safePracticeTasks = guardDraftItems(aiPracticeTasks, false);
        StringBuilder builder = new StringBuilder("\\section{")
                .append(escapeLatex(lectureTitle))
                .append("}\n");
        // The first real question shares the current page with its concise knowledge card. Later questions remain
        // independently printable, which preserves writing space without creating an almost empty overview page.
        boolean firstPrintableQuestion = true;
        for (TeachingKnowledgePointPack pack : packs) {
            builder.append("\\section{").append(escapeLatex(topicSectionHeading(pack.title()))).append("}\n");
            // Students need the same authorized source diagram to answer a figure-based question. Reuse only
            // the permission-checked sibling asset already attached to this knowledge-point pack; never expose a
            // remote Feishu URL or an unverified path in the student worksheet.
            String imagePath = pack.supportingEvidence().stream()
                    .map(TeachingEvidence::imagePath)
                    .filter(path -> path != null && !path.isBlank())
                    .findFirst()
                    .orElse("");
            // Do not print the figure in this overview block. appendStudentQuestion owns the same authorized image,
            // so each student question has exactly one nearby diagram instead of a detached duplicate on a prior page.
            // Student worksheets may reuse an authorized diagram, but never print source snippets or source-derived
            // results: that would both expose OCR noise and leak the teacher's answer into the student's task page.
            // 学生稿内容已由 Python 图独立生成。Java 仅在通过学生版泄漏过滤后，将其附在对应知识点题目旁。
            if (!safeKnowledgeNotes.isEmpty()) {
                builder.append("\\paragraph{知识速记}\n")
                        .append(latexItemize(safeKnowledgeNotes)).append("\n");
            }
            if (!safeRecognitionSignals.isEmpty()) {
                builder.append("\\paragraph{识别信号}\n")
                        .append(latexItemize(safeRecognitionSignals)).append("\n");
            }
            TeachingEvidence workedExample = pack.workedExample();
            String workedExampleText = workedExample == null ? "" : questionTextOnly(workedExample.snippet());
            String workedExampleImagePath = requiresAuthorizedFigure(workedExampleText)
                    ? firstExistingAuthorizedImagePath(workedExample)
                    : "";
            if (requiresAuthorizedFigure(workedExampleText) && workedExampleImagePath.isBlank()) {
                workedExampleImagePath = firstAuthorizedImageForQuestion(
                        questionTextOnly(workedExample.snippet()), pack.supportingEvidence());
            }
            appendStudentQuestion(builder, "例题", workedExample, workspace, workedExampleImagePath,
                    safePracticeTasks, !firstPrintableQuestion);
            if (workedExample != null && !isUnusableQuestionText(questionTextOnly(workedExample.snippet()))) {
                firstPrintableQuestion = false;
            }
            int variationIndex = 1;
            for (TeachingEvidence variation : pack.variations()) {
                String variationText = variation == null ? "" : questionTextOnly(variation.snippet());
                String variationImagePath = requiresAuthorizedFigure(variationText)
                        ? firstExistingAuthorizedImagePath(variation)
                        : "";
                if (requiresAuthorizedFigure(variationText) && variationImagePath.isBlank()) {
                    variationImagePath = firstAuthorizedImageForQuestion(
                            questionTextOnly(variation.snippet()), pack.supportingEvidence());
                }
                appendStudentQuestion(builder, variationIndex == 1 ? "变式练习" : "拓展变式",
                        variation, workspace, variationImagePath, List.of(), !firstPrintableQuestion);
                if (variation != null && !isUnusableQuestionText(questionTextOnly(variation.snippet()))) {
                    firstPrintableQuestion = false;
                }
                variationIndex += 1;
            }
        }
        return builder.toString();
    }


    /** Returns only an existing local asset whose source block is proven to match the current question. */
    static String firstAuthorizedImageForQuestion(
            String questionText,
            List<TeachingEvidence> supportingEvidence) {
        return supportingEvidenceForQuestion(questionText, supportingEvidence).stream()
                .map(TeachingEvidence::imagePath)
                .filter(path -> path != null && !path.isBlank())
                .filter(path -> Files.isRegularFile(Path.of(path)))
                .findFirst()
                .orElse("");
    }


    /** Returns the question row's own already permission-checked renderer path, never an inferred sibling asset. */
    static String firstExistingAuthorizedImagePath(TeachingEvidence evidence) {
        if (evidence == null || evidence.imagePath() == null || evidence.imagePath().isBlank()) {
            return "";
        }
        String path = evidence.imagePath().strip();
        return Files.isRegularFile(Path.of(path)) ? path : "";
    }


    /** Keeps a visual stem atomic: diagram-dependent mathematics cannot be rendered from text alone. */
    static boolean requiresAuthorizedFigure(String questionText) {
        return questionText != null && FIGURE_DEPENDENT_QUESTION.matcher(questionText).find();
    }


    /** Keeps student section names topic-owned instead of exposing a fixed template vocabulary. */
    static String evidenceHeading(String knowledgePoint) {
        String title = knowledgePoint == null || knowledgePoint.isBlank() ? "本节知识" : knowledgePoint.strip();
        return title + "：依据与信号";
    }


    static String writingHeading(String knowledgePoint) {
        String title = knowledgePoint == null || knowledgePoint.isBlank() ? "本节知识" : knowledgePoint.strip();
        return title + "：书写路径";
    }


    static void appendStudentQuestion(
            StringBuilder builder,
            String heading,
            TeachingEvidence question,
            int workspace,
            String authorizedImagePath,
            List<String> selfCheckTasks,
            boolean startOnNewPage) {
        if (question == null) {
            return;
        }
        String questionText = questionTextOnly(question.snippet());
        if (questionText.isBlank() || questionText.contains("题目内容待补充")) {
            // Keep malformed imported rows out of the student worksheet instead of exposing an empty placeholder.
            return;
        }
        if (requiresAuthorizedFigure(questionText)
                && (authorizedImagePath == null || authorizedImagePath.isBlank()
                || !Files.isRegularFile(Path.of(authorizedImagePath)))) {
            // Teachers and students see the same complete question boundary; a missing source asset omits the
            // question rather than asking the learner to infer an absent diagram.
            return;
        }
        if (startOnNewPage) {
            builder.append("\\clearpage\n");
        }
        builder.append("\\subsection*{").append(escapeLatex(heading)).append("}\n")
                .append("\\paragraph{题目}\n")
                .append(escapeLatex(questionText)).append("\n");
        // The same permission-checked figure belongs with the question, not only with the preceding
        // knowledge page. This preserves the printable question-image-workspace unit after \clearpage.
        if (authorizedImagePath != null && !authorizedImagePath.isBlank()) {
            builder.append(authorizedImageLatex(authorizedImagePath)).append("\n");
        }
        if (selfCheckTasks != null && !selfCheckTasks.isEmpty()) {
            builder.append("\\paragraph{自检任务}\n")
                    .append(latexItemize(selfCheckTasks)).append("\n");
        }
        builder.append("\n\\vspace{").append(workspace).append("em}\n");
    }


    /** Returns a student-safe first move for diagram questions without leaking the teacher conclusion. */
    static String studentQuestionHint(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (text.contains("4×4方格") || text.contains("4×4 方格")) {
            return "把四行所选方格的列号依次记下，先说明为什么它们构成一个不重复的排列，再处理最大和。";
        }
        if (text.contains("二面角") || text.contains("对折")) {
            return "先在折叠前的平面图中标出 EF、PD 与已知直角关系；证明题和二面角计算分别列出依据。";
        }
        return "先从题干圈出已知量和所求量，写明准备使用的定义、公式或定理，再完成推理。";
    }


    /**
     * Keeps the built-in scaffold compact so the later AI draft sections remain the primary readable content.
     */
    static List<String> teacherMethodCards(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingHandoutTemplateProfile template) {
        List<String> cards = new ArrayList<>();
        cards.add("先写出本讲对应的定义、公式、图像特征或空间关系，再进入计算或证明。");
        if (!evidence.isEmpty()) {
            cards.add("优先依据命中的教材/题库/教师资料组织讲评，不直接搬运 OCR 原文。");
        }
        cards.add("题型推进保持“识别条件 → 选择方法 → 写关键等式 → 回收答案与评分点”。");
        return cards.stream().distinct().limit(5).toList();
    }


    static List<String> studentMethodCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        List<String> cards = new ArrayList<>();
        cards.add("先圈出关键词，再判断对应的是定义、公式、图像性质还是题型方法。");
        cards.add("遇到参数、范围、符号或图形关系时，先处理边界条件。");
        if (!studentSafeQuestionText(request).isBlank()) {
            cards.add("本讲例题围绕“" + studentSafeQuestionText(request) + "”展开。");
        }
        if (!evidence.isEmpty()) {
            cards.add("先看教材或题源中的核心定义，再自己写第一步。");
        }
        return cards.stream().distinct().limit(4).toList();
    }



    static List<String> teacherBoardPlan(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingHandoutTemplateProfile template) {
        List<String> plan = new ArrayList<>();
        plan.add("先用 1 行话说清本讲主题和核心依据，再开始板书。");
        plan.add("板书顺序保持“写定义/公式 → 列出条件 → 立关键等式或图形关系 → 回收答案”。");
        if (!safeQuestionText(request).isBlank()) {
            plan.add("把题干中的关键词拆成已知条件、求解目标和第一步落点：" + safeQuestionText(request));
        }
        if (!evidence.isEmpty()) {
            plan.add("板书只保留与题目直接相关的定义、公式和条件，不粘贴资料原文或来源说明。");
        }
        return plan.stream().distinct().limit(5).toList();
    }


    static List<String> teacherChecklist(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingHandoutTemplateProfile template) {
        List<String> checklist = new ArrayList<>();
        checklist.add("核对教师版是否包含知识来源、题型识别、完整答案、追问和错因提醒。");
        checklist.add("核对学生版是否只保留知识点、题目、提示和足够作答留白。");
        checklist.add("检查分式、平方、不等号、根式是否按标准 LaTeX 渲染。");
        if (!evidence.isEmpty()) {
            checklist.add("抽查命中来源与讲义内容是否一致，避免把 OCR 碎片直接写进正文。");
        }
        if (!safeQuestionText(request).isBlank()) {
            checklist.add("确认题干与模板主线一致，不要把题型和例题讲偏。");
        }
        if (template.studentLectureStyle()) {
            checklist.add("确认学生版题号连续、留白充足，适合横版讲解和课堂打印。");
        }
        return checklist.stream().distinct().limit(6).toList();
    }


    static String studentKnowledgeSection(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> items = new ArrayList<>();
        items.add(fallbackHint);
        if (!safeQuestionText(request).isBlank()) {
            items.add("题目条件先拆成“已知什么、要求什么、先用什么”。");
        }
        if (!evidence.isEmpty()) {
            items.add("优先回忆命中证据里的定义、公式或题型信号，再开始作答。");
        }
        items.add("公式、定义、图像性质写清以后再进入计算，避免直接硬算。");
        return latexItemize(items.stream().distinct().limit(4).toList());
    }


    static List<String> studentKnowledgeCards(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> cards = new ArrayList<>();
        cards.add("先写本讲最核心的定义、公式和适用条件，再开始计算或证明。");
        if (!evidence.isEmpty()) {
            cards.add("先回到命中来源里的主定义或主公式，再决定第一步。来源：" + evidenceLabel(evidence.getFirst()));
        }
        if (!safeQuestionText(request).isBlank()) {
            cards.add("把题目拆成“已知条件、求解目标、第一步依据”三件事。");
        }
        cards.add(fallbackHint);
        return cards.stream().distinct().limit(4).toList();
    }


    static String studentKnowledgeCardsLatex(List<String> cards) {
        return latexItemize(cards);
    }


    static List<String> studentNoticeCards(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        List<String> notes = new ArrayList<>();
        notes.add("先核对定义域、参数是否为 0、符号方向和边界条件。");
        notes.add("若题目涉及图像、几何关系或位置关系，先画草图或标关键量。");
        if (!safeQuestionText(request).isBlank()) {
            notes.add("读题时先划出“已知什么、要求什么、第一步写什么”。");
        }
        if (!evidence.isEmpty()) {
            notes.add("教材或题源中的关键词先记下来，再开始计算。");
        }
        return notes.stream().distinct().limit(4).toList();
    }


    static List<String> studentPracticeTasks(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingTaskResponse.AiDraft aiDraft,
            String draftPractice) {
        List<String> tasks = new ArrayList<>();
        tasks.addAll(draftBlockLines(draftPractice));
        tasks.addAll(guardDraftItems(aiDraft == null ? List.of() : aiDraft.followUpQuestions(), false));
        for (TeachingEvidence item : questionBankEvidence(evidence)) {
            tasks.add(questionDifficulty(item) + "：" + questionTextOnly(item.snippet()));
        }
        tasks.addAll(defaultStudentExercises(request));
        return mergeDistinctItems(6, tasks).stream().limit(6).toList();
    }


    static List<String> defaultStudentExercises(TeachingTaskRequest request) {
        String goal = request.learningGoal() == null || request.learningGoal().isBlank()
                ? "本讲主题"
                : request.learningGoal().strip();
        String prompt = studentSafeQuestionText(request).isBlank()
                ? goal
                : studentSafeQuestionText(request);
        return List.of(
                "基础 1：先写出“" + goal + "”对应的定义、公式或图像特征。",
                "基础 2：围绕“" + prompt + "”写出第一步依据，并说明为什么这样设。",
                "基础 3：补全一组最小条件，判断本题能否直接套用核心公式。",
                "提高 1：把题目中的一个条件改成相近条件，说明解法哪里要调整。",
                "提高 2：保留主方法不变，补一题同类变式并完成关键一步。",
                "综合 1：整理本讲同类题的解法顺序，并写出最容易漏掉的一步。");
    }


    static List<String> teacherWideSlides(
            String questionSection,

            String questionType,
            String methodSteps,
            String answerPoints,
            String pitfalls,
            String followUps) {
        return List.of(
                "已知条件与目标：" + questionSection,
                "方法选择："
                        + (questionType.isBlank() ? "根据定义、性质和条件确定方法。" : flattenDraftBlock(questionType)),
                "推导与答案："
                        + (methodSteps.isBlank() ? "每一步都写依据。" : flattenDraftBlock(methodSteps))
                        + (answerPoints.isBlank() ? "" : " 结尾强调：" + flattenDraftBlock(answerPoints)),
                "易错点与追问："
                        + (pitfalls.isBlank() ? "" : " 易错点：" + flattenDraftBlock(pitfalls))
                        + (followUps.isBlank() ? "" : " 追问：" + flattenDraftBlock(followUps)))
                .stream()
                .map(TeachingWorkflowService::compactLectureCard)
                .toList();
    }


    /** Keeps a landscape card readable from a classroom screen and leaves room for spoken explanation. */
    static String compactLectureCard(String value) {
        String normalized = flattenDraftBlock(value);
        if (normalized.length() <= MAX_LECTURE_CARD_CHARACTERS) {
            return normalized;
        }
        int cutoff = MAX_LECTURE_CARD_CHARACTERS;
        for (int index = cutoff; index > cutoff / 2; index -= 1) {
            char current = normalized.charAt(index - 1);
            if (current == '。' || current == '；' || current == '，' || current == ';' || current == ',') {
                cutoff = index;
                break;
            }
        }
        return normalized.substring(0, cutoff).strip() + "……";
    }


    static String studentExampleSection(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            String fallbackHint) {
        List<String> items = new ArrayList<>();
        if (!studentSafeQuestionText(request).isBlank()) {
            items.add("先独立拆题：把“" + studentSafeQuestionText(request) + "”分成已知条件、目标和关键方法。");
        } else {
            items.add("先围绕本讲主题补出 1 道典型例题，再写第一步关键依据。");
        }
        items.add("作答时先写定义、公式或图形关系，再推进运算或证明。");
        if (!evidence.isEmpty()) {
            items.add("可参考命中来源中的核心定义或公式，答案由学生独立完成。");
        } else {
            items.add(fallbackHint);
        }
        return latexEnumerate(items.stream().distinct().limit(4).toList());
    }


    static String studentLectureTitle(TeachingTaskRequest request) {
        String goal = request.learningGoal() == null ? "" : request.learningGoal().strip();
        if (goal.contains("专题")) {
            return "专题  " + goal;
        }
        return "第 1 讲  " + goal;
    }


    /**
     * Removes workflow/control wording from the student-facing question while preserving ordinary mathematical text.
     * Teacher prompts may mention version checks, exports, internal prompts, or diagnostics; repeating those phrases in
     * a student worksheet leaks orchestration details even when no answer is exposed.
     */
    static String studentSafeQuestionText(TeachingTaskRequest request) {
        String value = safeQuestionText(request);
        if (value.isBlank()) {
            return "";
        }
        if (value.matches("(?is).*?(教师版|学生版|内部提示词|系统提示|模型诊断|不从教师版截取|生成后保存|导出\\s*PDF|工作流|智能体|子agent|子智能体).*")) {
            return "";
        }
        return value;
    }


    /** Removes whole AI-draft lines that describe orchestration rather than mathematics from the student worksheet. */
    static String sanitizeStudentWorkflowText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Pattern workflow = Pattern.compile(
                "(?i)(教师版|学生版|内部提示词|系统提示|模型诊断|不从教师版截取|生成后保存|导出\\s*PDF|工作流|智能体|子agent|子智能体)");
        return java.util.Arrays.stream(value.replace("\r\n", "\n").replace('\r', '\n').split("\n"))

                .map(String::strip)
                .filter(line -> !line.isBlank() && !workflow.matcher(line).find())
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
    }


    /**
     * Builds teacher-only question bank practice with answer snippets preserved.
     */
    static String teacherQuestionBankSection(List<TeachingEvidence> evidence) {
        List<TeachingEvidence> questions = questionBankEvidence(evidence);
        if (questions.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\\section{题库分层练习与答案}\n");
        int index = 1;
        for (TeachingEvidence item : questions) {
            String difficulty = questionDifficulty(item);
            String question = questionTextOnly(item.snippet());
            String answer = questionAnswerOnly(item.snippet());
            builder.append("\\subsection*{题 ").append(index).append("  ")
                    .append(escapeLatex(difficulty + " · " + questionTitleWithoutDifficulty(item)))
                    .append("}\n")
                    .append(escapeLatex(question))
                    .append("\n\n\\paragraph{答案要点}\n")
                    .append(escapeLatex(answer.isBlank() ? "题库未提供答案，需教师审校后补充。" : answer))
                    .append("\n\n\\paragraph{讲评提醒}\n")
                    .append(escapeLatex("先让学生说出第一步依据，再补完整解题链路。"))
                    .append("\n\n");
            index += 1;
        }
        return builder.toString();
    }


    /**
     * Builds student-safe question bank practice without answer or scoring leakage.
     */
    static String studentQuestionBankSection(TeachingTaskRequest request, List<TeachingEvidence> evidence, int blankSpaceEm) {
        List<TeachingEvidence> questions = questionBankEvidence(evidence);
        if (questions.isEmpty()) {
            return "";
        }
        List<String> tasks = new ArrayList<>();
        for (TeachingEvidence item : questions) {
            tasks.add(questionDifficulty(item) + "：" + questionTextOnly(item.snippet()));
        }
        return studentQuestionPages("题库分层练习", tasks, blankSpaceEm);
    }


    /**
     * Gives every student-facing question a complete page with an intentionally generous writing area.
     * The explicit page breaks also work in the PDFBox fallback renderer, so layout does not depend on XeLaTeX.
     */
    static String studentQuestionPages(String sectionTitle, List<String> items, int configuredWorkspaceEm) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        int space = Math.max(STUDENT_QUESTION_WORKSPACE_EM, configuredWorkspaceEm);
        StringBuilder builder = new StringBuilder("\\clearpage\n\\section{")
                .append(escapeLatex(sectionTitle))
                .append("}\n");
        int index = 1;
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String safeItem = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                    guardHandoutLatex(escapeLatex(item), false));
            if (safeItem.isBlank()) {
                continue;
            }
            if (index > 1) {
                builder.append("\\clearpage\n");
            }
            builder.append("\\subsection*{第 ").append(index).append(" 题}\n")
                    .append("\\paragraph{题目}\n")
                    .append(safeItem)
                    .append("\n\\vspace{").append(space).append("em}\n");
            index += 1;
        }
        return index == 1 ? "" : builder.toString();
    }


    static List<TeachingEvidence> questionBankEvidence(List<TeachingEvidence> evidence) {
        return evidence.stream()
                .filter(item -> "QUESTION_BANK".equals(item.sourceScope()))
                .sorted(Comparator.comparingInt(TeachingWorkflowService::questionDifficultyRank))
                .toList();
    }


    static int questionDifficultyRank(TeachingEvidence item) {
        String difficulty = questionDifficulty(item);
        if (difficulty.contains("基础") || difficulty.equalsIgnoreCase("easy")) {
            return 0;
        }
        if (difficulty.contains("提高") || difficulty.contains("中等") || difficulty.equalsIgnoreCase("medium")) {
            return 1;
        }
        if (difficulty.contains("压轴") || difficulty.contains("困难") || difficulty.equalsIgnoreCase("hard")) {
            return 2;
        }
        return 3;
    }


    static String questionDifficulty(TeachingEvidence item) {
        String title = item.sourceTitle() == null ? "" : item.sourceTitle();
        int index = title.indexOf("难度：");
        if (index >= 0) {
            return title.substring(index + "难度：".length()).strip();
        }
        index = title.indexOf("难度:");
        if (index >= 0) {
            return title.substring(index + "难度:".length()).strip();
        }
        return "未标难度";
    }


    static String questionTitleWithoutDifficulty(TeachingEvidence item) {
        String title = item.sourceTitle() == null ? "" : item.sourceTitle().strip();
        if (title.isBlank()) {
            return "题库题目";
        }
        return title
                .replaceAll("\\s*/\\s*难度[:：].*$", "")
                .replaceAll("\\s*（?难度[:：].*?）?\\s*$", "")
                // “用户题目 /” is a retrieval transport label created by the fallback pack, not a printable title.
                // Removing it at the shared title boundary keeps teacher, student, and projection versions aligned.
                .replaceFirst("^用户题目\\s*/\\s*", "")
                .strip();
    }


    static String questionTextOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "题目内容待补充。";
        }
        String[] parts = repairMojibake(snippet).split("答案要点：", 2);
        String stem = parts[0].replace('\r', '\n').strip();
        // A known import failure writes a short source preview, then a standalone “题目” label, then the complete
        // source question.  Retaining both prints the same stem twice and makes the later solution look unrelated.
        // The post-label part is the complete atomic question; if it is empty, keep the original source text.
        String[] labeledParts = STANDALONE_QUESTION_LABEL.split(stem, 2);
        if (labeledParts.length == 2 && !labeledParts[1].isBlank()) {
            stem = labeledParts[1];
        }
        // The source title stays in retrieval/audit data. Historical OCR prefixes such as “赵礼显数学作业 1.”
        // are not part of a problem statement and make an otherwise neutral handout leak a third-party brand.
        stem = stem.strip();
        stem = PRINTABLE_SOURCE_WORKBOOK_PREFIX.matcher(stem).replaceFirst("");
        stem = PRINTABLE_SOURCE_BRAND_PREFIX.matcher(stem).replaceFirst("");
        return stem.replaceAll("\\s+", " ").strip();
    }


    static String questionAnswerOnly(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        String[] parts = repairMojibake(snippet).split("答案要点：", 2);
        if (parts.length < 2) {
            return "";
        }
        String answer = QuestionBankAnswerFormatter.format(parts[1]);
        return "答案要点：" + answer;
    }


    /**
     * Builds a compact teacher-facing knowledge point label from the learning goal and top evidence.
     */
    static String teacherKnowledgePoint(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        String source = evidence.isEmpty() ? "当前未命中公开教材或教师私有资料" : evidence.getFirst().sourceTitle();
        return escapeLatex(request.learningGoal() + "；来源：" + source);
    }


    /**
     * 最小 LaTeX 转义，避免用户输入中的特殊字符破坏讲义结构。
     */
    static String escapeLatex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        // XeLaTeX's configured CJK font intentionally does not promise glyph coverage for mathematical symbols.
        // Convert source Unicode before the generic sanitizer so triangle/angle relations never degrade to visible
        // square boxes in a printed geometry question.
        String sourceMathNormalized = value
                .replace("△", "$\\triangle$")
                .replace("∠", "$\\angle$")
                .replace("⊥", "$\\perp$");
        String normalized = com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer.sanitizeFeishuMath(sourceMathNormalized)
                // JSON producers occasionally interpret LaTeX commands as JSON escapes (\b, \t, \f). Restore the
                // intended command before splitting math/text; otherwise XeLaTeX rejects the control character.
                .replace("\u0008oldsymbol", "\\boldsymbol")
                .replace("\u0009heta", "\\theta")
                .replace("\u000C rac", "\\frac")
                .replace("\u000C", "")
                .replace("\u0008", "")
                .replace("\u0009", " ");
        StringBuilder builder = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < normalized.length(); index += 1) {
            if (normalized.startsWith("$$", index)) {
                builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexTextWithBlanks(segment.toString()));
                segment.setLength(0);
                builder.append("$$");
                math = !math;
                index += 1;
                continue;
            }
            char character = normalized.charAt(index);
            if (character == '$') {

                builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexTextWithBlanks(segment.toString()));
                segment.setLength(0);
                builder.append('$');

                math = !math;
            } else {
                segment.append(character);
            }
        }
        builder.append(math ? sanitizeMathSegment(segment.toString()) : escapeLatexTextWithBlanks(segment.toString()));
        return builder.toString();
    }


    static String escapeLatexTextWithBlanks(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = BLANK_PLACEHOLDER.matcher(value);
        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            builder.append(escapeLatexText(value.substring(cursor, matcher.start())));
            int width = Math.max(4, Math.min(10, matcher.group().length() + 1));
            builder.append("\\underline{\\hspace{").append(width).append("em}}");
            cursor = matcher.end();
        }
        builder.append(escapeLatexText(value.substring(cursor)));
        return builder.toString();
    }


    static String escapeLatexText(String value) {
        return value
                .replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("#", "\\#")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("^", "\\textasciicircum{}")
                .replace("~", "\\textasciitilde{}");
    }


    static String sanitizeMathSegment(String value) {
        return value
                // A previous text escape may have reached a model-provided $...$ segment. Restore exponent
                // notation before XeLaTeX sees it; \textasciicircum is invalid inside math mode.
                .replace("\\textasciicircum{}", "^")
                .replace("\\textbackslash{}frac", "\\frac")
                .replace("\\textbackslash{}sqrt", "\\sqrt")
                .replace("\\textbackslash{}sin", "\\sin")
                .replace("\\textbackslash{}cos", "\\cos")
                .replace("\\textbackslash{}tan", "\\tan")
                .replace("\\textbackslash{}ln", "\\ln")
                .replace("\\textbackslash{}log", "\\log")
                .replace("\\textbackslash{}pi", "\\pi")
                .replace("\\textbackslash{}theta", "\\theta")
                .replace("\\textbackslash{}alpha", "\\alpha")
                .replace("\\textbackslash{}beta", "\\beta")
                .replace("\\textbackslash{}gamma", "\\gamma")
                .replace("\\textbackslash{}Delta", "\\Delta")
                .replace("\\textbackslash{}infty", "\\infty")
                .replace("\\textbackslash{}leq", "\\leq")
                .replace("\\textbackslash{}geq", "\\geq")
                .replace("\\textbackslash{}neq", "\\neq")
                .replace("\\textbackslash{}cdot", "\\cdot")
                .replace("\\textbackslash{}times", "\\times")
                .replace("\\textbackslash{}to", "\\to");
    }


    static String safeQuestionText(TeachingTaskRequest request) {
        if (request.questionText() == null || request.questionText().isBlank()) {
            return "";
        }
        return TASK_CONTROL_LINE.matcher(request.questionText().replace('\r', '\n'))
                .replaceAll("")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }


    /** Removes workflow controls from topic text before graph/image heuristics inspect the mathematical subject. */
    static String safeTaskText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return TASK_CONTROL_LINE.matcher(value.replace('\r', '\n'))
                .replaceAll("")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }


    static List<String> teacherDraftLabels() {
        return List.of("知识定位", "题型识别", "方法步骤", "例题详解", "答案与评分点", "易错提醒", "课堂追问");
    }


    static List<String> studentDraftLabels() {
        return List.of("知识速记", "题型识别", "例题任务", "练习任务", "作答提醒");
    }


    static String labeledDraftSections(String text, List<String> labels, String fallbackTitle) {
        List<LabeledDraftBlock> blocks = parseLabeledDraftBlocks(text, labels, fallbackTitle);
        StringBuilder builder = new StringBuilder();
        for (LabeledDraftBlock block : blocks) {
            builder.append("\\subsection*{")
                    .append(escapeLatex(block.label()))
                    .append("}\n")
                    .append(formatDraftContentAsLatex(block.content()))
                    .append("\n\n");
        }
        return builder.toString();
    }


    static String draftBlockContent(String text, List<String> labels, String targetLabel) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return parseLabeledDraftBlocks(text, labels, targetLabel).stream()
                .filter(block -> targetLabel.equals(block.label()))
                .map(LabeledDraftBlock::content)
                .findFirst()
                .orElse("");
    }


    static List<String> draftBlockLines(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String rawLine : content.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip()
                    .replaceFirst("^[0-9]+[.、)]\\s*", "")
                    .replaceFirst("^[-•·]\\s*", "")
                    .strip();
            if (!line.isBlank()) {
                items.add(line);
            }
        }
        return items;
    }


    @SafeVarargs
    static List<String> mergeDistinctItems(int limit, List<String>... groups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String item : group) {
                String normalized = guardHandoutLatex(item, true).replaceAll("\\s+", " ").strip();
                if (!normalized.isBlank()) {
                    merged.add(normalized);
                }
                if (merged.size() >= limit) {
                    return List.copyOf(merged);
                }
            }
        }
        return List.copyOf(merged);
    }


    static String flattenDraftBlock(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").strip();
    }


    static String contentOrFallback(String content, String fallbackLatex) {
        if (content == null || content.isBlank()) {
            return fallbackLatex == null ? "" : fallbackLatex;
        }
        return formatDraftContentAsLatex(content);
    }


    static String latexEnumerateWithWorkspace(List<String> items, int workspaceEm) {
        if (items == null || items.isEmpty()) {
            return "\n";
        }
        int space = boundedEm(workspaceEm, 5, 12, 6);
        StringBuilder builder = new StringBuilder("\n\\begin{enumerate}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append("\\par\n")
                    .append("\\vspace{").append(space).append("em}\n");
        }
        return builder.append("\\end{enumerate}\n").toString();
    }


    static int boundedEm(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }


    static String formatDraftContentAsLatex(String content) {
        String source = content == null ? "" : content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (source.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        List<String> ordered = new ArrayList<>();
        List<String> unordered = new ArrayList<>();
        for (String rawLine : source.split("\n")) {
            String line = rawLine.strip();

            if (line.isBlank()) {
                flushDraftList(builder, ordered, true);
                flushDraftList(builder, unordered, false);
                builder.append("\\par\n");
                continue;
            }
            Matcher orderedMatcher = DRAFT_ORDERED_LINE.matcher(line);
            Matcher bulletMatcher = DRAFT_BULLET_LINE.matcher(line);
            if (orderedMatcher.matches()) {
                flushDraftList(builder, unordered, false);
                ordered.add(orderedMatcher.group(1).strip());
                continue;
            }
            if (bulletMatcher.matches()) {
                flushDraftList(builder, ordered, true);
                unordered.add(bulletMatcher.group(1).strip());
                continue;
            }
            flushDraftList(builder, ordered, true);
            flushDraftList(builder, unordered, false);
            builder.append(escapeLatex(line)).append("\\par\n");
        }
        flushDraftList(builder, ordered, true);
        flushDraftList(builder, unordered, false);
        return builder.toString();
    }


    static void flushDraftList(StringBuilder builder, List<String> items, boolean ordered) {
        if (items.isEmpty()) {
            return;
        }
        builder.append(ordered ? "\\begin{enumerate}\n" : "\\begin{itemize}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append('\n');
        }
        builder.append(ordered ? "\\end{enumerate}\n" : "\\end{itemize}\n");
        items.clear();
    }


    static List<LabeledDraftBlock> parseLabeledDraftBlocks(String text, List<String> labels, String fallbackTitle) {
        String source = text == null ? "" : text.strip();
        if (source.isBlank()) {
            return List.of();
        }
        List<LabelPosition> positions = new ArrayList<>();
        for (String label : labels) {
            String marker = "【" + label + "】";
            int from = 0;
            while (from < source.length()) {
                int start = source.indexOf(marker, from);
                if (start < 0) {
                    break;
                }
                positions.add(new LabelPosition(label, start, start + marker.length()));
                from = start + marker.length();
            }
        }
        positions.sort(Comparator.comparingInt(LabelPosition::start));
        if (positions.isEmpty()) {
            return List.of(new LabeledDraftBlock(fallbackTitle, source));
        }
        List<LabeledDraftBlock> blocks = new ArrayList<>();
        String prefix = source.substring(0, positions.getFirst().start()).strip();
        if (!prefix.isBlank()) {
            blocks.add(new LabeledDraftBlock(fallbackTitle, prefix));
        }
        for (int index = 0; index < positions.size(); index += 1) {
            LabelPosition current = positions.get(index);
            int nextStart = index + 1 < positions.size() ? positions.get(index + 1).start() : source.length();
            String content = source.substring(current.end(), nextStart).strip();
            if (!content.isBlank()) {
                blocks.add(new LabeledDraftBlock(current.label(), content));
            }
        }
        return List.copyOf(blocks);
    }


    static String latexItemize(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "\n";
        }
        StringBuilder builder = new StringBuilder("\n\\begin{itemize}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append('\n');
        }
        return builder.append("\\end{itemize}\n").toString();
    }


    static String latexEnumerate(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "\n";
        }
        StringBuilder builder = new StringBuilder("\n\\begin{enumerate}\n");
        for (String item : items) {
            builder.append("\\item ").append(escapeLatex(item)).append('\n');
        }
        return builder.append("\\end{enumerate}\n").toString();
    }


    static String difficultyBands(TeachingHandoutTemplateProfile template) {

        List<String> bands = template.summary().difficultyBands();
        if (bands == null || bands.isEmpty()) {
            return "基础、提高";
        }
        return String.join("、", bands);
    }


    /**
     * Converts only an exact, readable evidence image into the internal marker before a model-authored lecture card
     * is persisted. This keeps a “如图” card and its source-bound asset in the same publication unit; asset ids and
     * paths are never exposed to the model or rendered as text.
     */
    static List<String> bindAuthorizedLectureFigures(List<String> cards, List<TeachingEvidence> evidence) {
        if (cards == null || cards.isEmpty() || evidence == null || evidence.isEmpty()) {
            return cards == null ? List.of() : cards;
        }
        return cards.stream().map(card -> {
            if (card == null || card.isBlank() || !requiresAuthorizedFigure(card)) {
                return card;
            }
            // A Writer can only name opaque asset ids, not paths. Remove any marker it echoed and replace it with the
            // exact verified evidence asset below; retaining the echoed marker could preserve a stale path after a
            // container recreation and would defeat the question-scoped authorization check.
            String cardWithoutUnverifiedMarkers = TeachingHandoutPdfExportService.IMAGE_MARKER.matcher(card)
                    .replaceAll("").strip();
            // An opaque asset id can only select the already authorized evidence row that supplied it. This is more
            // precise than text similarity when a retrieved source window contains neighbouring colour variations.
            List<TeachingEvidence> assetReferencedEvidence = evidence.stream()
                    .filter(item -> item.assetIds().stream().anyMatch(assetId -> cardWithoutUnverifiedMarkers.contains(assetId)))
                    .toList();
            List<TeachingEvidence> matchingEvidence = assetReferencedEvidence.isEmpty()
                    ? supportingEvidenceForQuestion(cardWithoutUnverifiedMarkers, evidence)
                    : assetReferencedEvidence;
            return matchingEvidence.stream()
                    .map(TeachingEvidence::imagePath)
                    .filter(path -> path != null && !path.isBlank())
                    .filter(path -> Files.isRegularFile(Path.of(path)))
                    .findFirst()
                    .map(path -> cardWithoutUnverifiedMarkers + "\n" + TeachingHandoutPdfExportPolicyPartB.toImageMarker(
                            new TeachingHandoutPdfExportService.HandoutImage("题图", path)))
                    .orElse(card);
        }).toList();
    }

    /**
     * 教学任务阶段计时器；只记录阶段耗时，不保存业务内容，避免日志泄露学生题目。
     */
    static TeachingDraftSections collectDraftSections(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            TeachingTaskResponse.AiDraft aiDraft) {
        String questionSection = safeQuestionText(request).isBlank()
                ? "围绕学习目标设计例题、变式题和课堂追问。"
                : safeQuestionText(request);
        // Python 图输出与检索证据使用同一公式规范化入口，避免模型草稿绕过斜杠分式转换。
        String teacherExplanation = aiDraft == null ? "" : guardDraftText(
                com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer.sanitizeFeishuMath(aiDraft.teacherExplanation()), true);
        String studentWorksheet = aiDraft == null ? "" : guardDraftText(
                com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer.sanitizeFeishuMath(aiDraft.studentHint()), false);
        String questionType = draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别");
        String methodSteps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤");
        String answerPoints = draftBlockContent(teacherExplanation, teacherDraftLabels(), "答案与评分点");
        String draftPitfalls = draftBlockContent(teacherExplanation, teacherDraftLabels(), "易错提醒");
        String draftFollowUps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "课堂追问");
        String draftPractice = draftBlockContent(studentWorksheet, studentDraftLabels(), "练习任务");
        List<String> risks = new ArrayList<>();
        if (aiDraft == null || !aiDraft.structured()) {
            risks.add("ai_draft_unstructured");
        }
        if (teacherExplanation.isBlank()) {
            risks.add("teacher_explanation_missing");
        }
        if (studentWorksheet.isBlank()) {
            risks.add("student_worksheet_missing");
        } else {
            risks.add("student_answer_leakage_review_required");
        }
        if (evidence.isEmpty()) {
            risks.add("source_grounding_missing");
        }
        List<String> lectureCards = aiDraft == null || aiDraft.lectureContent() == null || aiDraft.lectureContent().isBlank()
                ? teacherWideSlides(
                        questionSection,
                        questionType,
                        methodSteps,
                        answerPoints,
                        draftPitfalls,
                        draftFollowUps)
                : List.of(guardDraftText(
                        com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer.sanitizeFeishuMath(aiDraft.lectureContent()), false));
        lectureCards = bindAuthorizedLectureFigures(lectureCards, evidence);
        if (!lectureCards.isEmpty()) {
            risks.add(aiDraft != null && aiDraft.lectureContent() != null && !aiDraft.lectureContent().isBlank()
                    ? "lecture_cards_from_python_handout"
                    : "lecture_cards_derived_from_teacher_outline");
        }
        return TeachingDraftSectionCollector.collect(
                teacherExplanation,
                studentWorksheet,
                lectureCards,
                studentPracticeTasks(request, evidence, aiDraft, draftPractice),
                evidence.stream().map(TeachingWorkflowService::evidenceRef).distinct().toList(),
                risks);
    }
}
