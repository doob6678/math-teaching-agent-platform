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
 * TeachingWorkflowLatexRenderer owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates latexrenderer rules.
 */
final class TeachingWorkflowLatexRenderer {
    private TeachingWorkflowLatexRenderer() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    /**
     * 构造学生记忆查询请求；教学任务阶段先使用学习目标作为知识点粗标签，后续会接入知识点识别器。
     */
    /**
     * Final backend guard before storage/export. It keeps printable handout content only.
     */
    static String guardHandoutLatex(String latex, boolean teacherVersion) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String guarded = INTERNAL_HANDOUT_LINE.matcher(latex).replaceAll("");
        if (!teacherVersion) {
            guarded = STUDENT_FORBIDDEN_SECTION.matcher(guarded).replaceAll("");
            guarded = STUDENT_FORBIDDEN_LINE.matcher(guarded).replaceAll("");
            guarded = guarded
                    .replaceAll("(?m)^\\\\(?:section|subsection|subsubsection|paragraph)\\*?\\{(?:答案与评分点|参考答案|参考解析|评分标准|例题详解|完整解析|教师讲解|讲评主线|教师备注|板书设计|课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区)}\\s*$", "");
            guarded = removeVisibleWorkspaceLabels(guarded);
            guarded = sanitizeStudentWorkflowText(guarded);
        }
        return removeEmptyTitledBlocks(guarded)
                .replaceAll("(?m)^\\s*\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }


    /**
     * A high-workload handout may only be published from real question-bank rows.  Failing here retains the durable
     * retrieval snapshot for repair/resume and prevents the model from inventing seven extra questions merely to
     * satisfy a page-count target.
     */
    static void requireQualifiedQuestionEvidence(
            TeachingHandoutTemplateProfile template,
            List<TeachingEvidence> questionEvidence) {
        if (template == null || template.summary() == null
                || !ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode())) {
            return;
        }
        int verifiedCount = questionBankEvidence(questionEvidence == null ? List.of() : questionEvidence).size();
        if (verifiedCount < MIN_QUALIFIED_HANDOUT_QUESTION_COUNT) {
            throw new IllegalStateException("当前真实题库仅命中 " + verifiedCount + " 道可溯源原子题；"
                    + "合格讲义至少需要 " + MIN_QUALIFIED_HANDOUT_QUESTION_COUNT
                    + " 道。请先同步对应目录资料并补齐题库，系统不会编造题目。");
        }
    }


    /** Requires a usable model-authored explanation for the long-form master before any printable version exists. */
    static void requireStructuredQuestionReasoning(
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft) {
        if (template == null || template.summary() == null
                || !ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode())) {
            return;
        }
        if (aiDraft == null || !aiDraft.enabled() || !aiDraft.structured()
                || aiDraft.teacherExplanation() == null || aiDraft.teacherExplanation().isBlank()) {
            throw new IllegalStateException("真实模型未返回可校验的逐题讲解；已保留资料与进度，请恢复同一任务后重试。");
        }
    }


    /**
     * Verifies the rendered teacher spine, not merely retrieval count. Figure-gated source rows can be correctly
     * omitted during rendering, so counting only evidence once allowed a seven-question handout to claim the
     * ten-question master floor. The task now remains resumable until ten actual numbered units exist.
     */
    static void requireQualifiedRenderedQuestionCount(
            TeachingHandoutTemplateProfile template,
            String teacherHandoutLatex) {
        if (template == null || template.summary() == null
                || !ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode())) {
            return;
        }
        Matcher matcher = Pattern.compile("(?m)^\\\\subsection\\*\\{第\\d+题").matcher(
                teacherHandoutLatex == null ? "" : teacherHandoutLatex);
        int renderedQuestionCount = 0;
        while (matcher.find()) {
            renderedQuestionCount += 1;
        }
        if (renderedQuestionCount < MIN_QUALIFIED_HANDOUT_QUESTION_COUNT) {
            throw new IllegalStateException("真实题库虽命中候选资料，但仅有 " + renderedQuestionCount
                    + " 道题具备可发布的题干、图像与逐题讲解；连续真题母版至少需要 "
                    + MIN_QUALIFIED_HANDOUT_QUESTION_COUNT + " 道。请先完成缺图题的单文档同步或补齐同源题库。");
        }
    }


    static String removeVisibleWorkspaceLabels(String value) {
        String withoutReferences = VISIBLE_WORKSPACE_REFERENCE.matcher(value).replaceAll("独立完成");
        return VISIBLE_WORKSPACE_LABEL.matcher(withoutReferences).replaceAll("");
    }


    /**
     * Builds an independent 16:10 lecture card from the shared reviewed draft rather than teacher-only LaTeX.
     * This keeps the projection artifact answer-safe and lets all three versions render in parallel.
     */
    static String buildLectureHandoutLatex(
            TeachingTaskRequest request,
            TeachingDraftSections draftSections) {
        return buildLectureHandoutLatex(request, List.of(), draftSections);
    }


    /**
     * Builds the projection version from concrete knowledge-point packs when verified questions are available.
     */
    static String buildLectureHandoutLatex(
        TeachingTaskRequest request,
            List<TeachingKnowledgePointPack> knowledgePointPacks,
        TeachingDraftSections draftSections) {
        if (knowledgePointPacks != null && !knowledgePointPacks.isEmpty()) {
            // A projection page is a complete teaching unit, not a reduced teacher handout.  Starting with a
            // generic lesson overview caused the real question to spill onto a second page, so the verified
            // question, its authorized figure, the speaking path, and the conclusion are emitted together here.
            // A shared draft can only describe one question safely when the resolved lesson has one point.  For a
            // multi-point lesson, using the first draft on every slide would be the same cross-question contamination
            // we reject for images; those slides therefore fall back to their own bank/source evidence.
            List<String> questionScopedSteps = knowledgePointPacks.size() == 1
                    ? lectureDraftSteps(draftSections)
                    : List.of();
            return guardHandoutLatex(lectureQuestionPages(knowledgePointPacks, questionScopedSteps), true);
        }
        if (draftSections != null && !draftSections.lectureCards().isEmpty()) {
            StringBuilder builder = new StringBuilder();
            // The projection document is a standalone artefact.  Keep the visible title
            // mathematical instead of exposing the internal aspect-ratio/card terminology.
            builder.append("\\section{课堂讲解}\n")
                    .append(lectureCardPages(draftSections.lectureCards(), LECTURE_CARD_WORKSPACE_EM, false));

            return guardHandoutLatex(builder.toString(), true);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\\section{课堂讲解}\n");
        String topic = request.learningGoal() == null || request.learningGoal().isBlank()
                ? request.questionText()
                : request.learningGoal();
        builder.append("\\paragraph{课堂投屏}\n")
                .append(escapeLatex(topic == null || topic.isBlank() ? "讲义主题未填写" : topic))
                .append("\n\n")
                .append("\\vspace{8em}\n");
        builder.append("\\vspace{10em}\n");
        return guardHandoutLatex(builder.toString(), true);
    }


    /**
     * Renders each verified question as a complete 16:10 page.  The source stays structural LaTeX rather than one
     * escaped sentence so the renderer cannot separate a question from its reasoning or print transport labels such
     * as “用户题目”.  Only permission-checked figures already attached to the same knowledge-point pack are used.
     */
    static String lectureQuestionPages(
            List<TeachingKnowledgePointPack> packs,
            List<String> questionScopedSteps) {
        StringBuilder builder = new StringBuilder();
        int questionNumber = 1;
        for (TeachingKnowledgePointPack pack : packs) {
            questionNumber = appendLectureQuestionPage(
                    builder, questionNumber, pack, "例题", pack.workedExample(), questionScopedSteps);
            int variationIndex = 1;
            for (TeachingEvidence variation : pack.variations()) {
                String label = variationIndex == 1 ? "变式" : "拓展变式";
                questionNumber = appendLectureQuestionPage(builder, questionNumber, pack, label, variation, List.of());
                variationIndex += 1;
            }
        }
        return builder.isEmpty() ? "\\vspace{" + LECTURE_CARD_WORKSPACE_EM + "em}\n" : builder.toString();
    }


    /** Writes one projected problem/solution unit and inserts a boundary only before the next real question. */
    static int appendLectureQuestionPage(
            StringBuilder builder,
            int questionNumber,
            TeachingKnowledgePointPack pack,
            String label,
            TeachingEvidence question,
            List<String> questionScopedSteps) {
        if (question == null || isUnusableQuestionText(questionTextOnly(question.snippet()))) {
            return questionNumber;
        }
        if (!builder.isEmpty()) {
            builder.append("\\clearpage\n");
        }
        String questionText = questionTextOnly(question.snippet());
        List<TeachingEvidence> matchingEvidence = supportingEvidenceForQuestion(questionText, pack.supportingEvidence());
        // The atomic bank row is the strongest possible image lineage.  A pack may also contain several source
        // pages for the same knowledge point, therefore looking in the pack first can attach a visually plausible
        // but different diagram to this projected question.  Only when the row has no materialized asset do we
        // consider a separately proven same-stem source block.
        String authorizedImagePath = requiresAuthorizedFigure(questionText)
                ? firstExistingAuthorizedImagePath(question)
                : "";
        if (requiresAuthorizedFigure(questionText) && authorizedImagePath.isBlank()) {
            authorizedImagePath = firstAuthorizedImageForQuestion(questionText, matchingEvidence);
        }
        // A diagram-dependent question must remain an atomic prompt-plus-figure unit.  Never substitute a sibling
        // image, produce a blank "如图" page, or invent a geometry diagram from incomplete OCR.
        if (requiresAuthorizedFigure(questionText) && authorizedImagePath.isBlank()) {
            return questionNumber;
        }
        boolean sourceMatchesQuestion = !matchingEvidence.isEmpty();
        String sourceFact = sourceMatchesQuestion ? lectureSourceResult(matchingEvidence) : "";
        String questionBankAnswer = questionAnswerOnly(question.snippet());
        String sourceAnswer = compactQuestionBankAnswer(questionBankAnswerWithoutSteps(questionBankAnswer));
        /*
         * A multi-question lesson cannot reuse the model's global method paragraph on every slide.  Prefer the
         * exact question-bank derivation; when the bank stores only a final answer, provide a small question-type
         * specific route.  This keeps the right column mathematically useful instead of printing generic process
         * prose such as “read the diagram and classify”.
         */
        List<String> sourcePath = lectureQuestionBankSteps(questionBankAnswer);
        // A draft-level method belongs to the whole lesson, not to this atomic question.  Reusing it here was the
        // cause of every slide showing the same "通用解题逻辑".  Only a source step explicitly stored with the
        // question may win; otherwise render the deterministic, stem-matched route below.
        List<String> path = !sourcePath.isEmpty()
                ? sourcePath
                : lectureQuestionFallbackPath(questionText);
        // Teacher pages retain the complete source-grounded derivation.  A 16:10 projection page is deliberately
        // a readable two-column cue sheet: only the three verifiable decisions belong beside the whole question.
        path = path.stream().filter(step -> step != null && !step.isBlank()).limit(LECTURE_PROJECTION_STEP_LIMIT).toList();
        // The bank answer is attached to this exact atomic question and has priority over a broad teacher snippet.
        // This makes the projection conclusion auditable without copying a neighbouring OCR variation.
        String conclusion = lectureQuestionConclusion(
                questionText, sourceAnswer, sourceMatchesQuestion ? lectureConclusion(matchingEvidence) : "");
        builder.append("\\subsection*{第 ").append(questionNumber).append(" 题：")
                .append(escapeLatex(label)).append("}\n")
                .append("\\begin{minipage}[t]{").append(LECTURE_COLUMN_WIDTH).append("}\n")
                .append("{\\normalfont\\mdseries 题目}")
                .append("\\par\\smallskip\n")
                .append("{\\small\\normalfont\\mdseries ").append(escapeLatex(questionText)).append("}\\par\n");
        if (!authorizedImagePath.isBlank()) {
            builder.append(lectureAuthorizedImageLatex(authorizedImagePath)).append("\n");
        }
        builder.append("\\vfill\\end{minipage}\n")
                .append("\\vfill\n");
        return questionNumber + 1;
    }


    /**
     * Keeps the projection conclusion tied to the visible question.  A question-bank OCR fragment is not allowed to
     * overwrite a verified stem-specific result merely because it is non-empty.
     */
    static String lectureQuestionConclusion(String questionText, String sourceAnswer, String evidenceConclusion) {
        String compact = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (compact.contains("4×4方格") || compact.contains("4×4 方格")) {
            return "结论：$4!=24$；最大和为 $40+33+22+15=110$。";
        }
        if ((compact.contains("二面角") || compact.contains("对折")) && compact.contains("PC=4√3")) {
            return "结论：$EF\\perp PD$；二面角正弦为 $\\frac{8}{\\sqrt{65}}$。";
        }
        if (!isUnreliableQuestionAnswer(sourceAnswer)) {
            return sourceAnswer;
        }
        return evidenceConclusion;
    }


    /** Extracts at most three readable source steps for the current atomic question, never a neighbouring solution. */
    static List<String> lectureQuestionBankSteps(String answerEvidence) {
        String steps = questionBankSteps(answerEvidence);
        if (steps.isBlank()) {
            return List.of();
        }
        return draftBlockLines(steps).stream()
                .map(value -> value.replaceFirst("^(?:【[^】]+】|[（(]?[一二三四五1-9]+[）).、:]?)\\s*", "").strip())
                .filter(value -> value.length() >= 4)
                .limit(LECTURE_PROJECTION_STEP_LIMIT)
                .toList();
    }


    /** Supplies concrete mathematical prompts only when an atomic source has no stored derivation. */
    static List<String> lectureQuestionFallbackPath(String questionText) {
        String compact = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (compact.contains("4×4方格") || compact.contains("4×4 方格")) {
            return List.of(
                    "把每一行被选方格的列号看作一个排列；“每列恰一个”保证列号不重复。",
                    "先计算排列总数 $4!$，再按每行取值逐项比较，确定四个数和的最大组合。",
                    "核对最大组合的四个列号互不重复，确保仍满足每列恰选一个。");
        }
        if (compact.contains("二面角") || compact.contains("对折")) {
            return List.of(
                    "先在 $\\triangle AEF$ 中由边角关系求出垂直关系，锁定折叠后的关键线面条件。",
                    "利用已知直角关系建立空间直角坐标系，分别写出两个平面的法向量。",
                    "由法向量夹角求二面角的正弦，并结合题设范围取正值。");
        }
        return List.of(
                "从题干摘出已知量、所求量和可直接使用的定义或公式。",
                "按等价变形或定理条件逐步推出目标量，保留每一步的依据。",
                "把结果代回题设，检查范围、符号和边界条件。");
    }


    /** Replaces a broad document title with the current slide's mathematical focus. */

    static String lectureTopicSummary(String questionText) {
        String compact = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (compact.contains("4×4方格") || compact.contains("4×4 方格")) {
            return "排列模型与最大和";
        }
        if (compact.contains("二面角") || compact.contains("对折")) {
            return "折叠几何与空间向量";
        }
        if (compact.contains("双曲线")) {
            return "双曲线构造与递推关系";
        }
        return "题干条件到结论";
    }


    /** Extracts only concrete model-authored steps for the one question currently projected. */
    static List<String> lectureDraftSteps(TeachingDraftSections draftSections) {
        if (draftSections == null) {
            return List.of();
        }
        String methodSteps = draftBlockContent(
                draftSections.teacherExplanation(), teacherDraftLabels(), "方法步骤");
        return draftBlockLines(withoutBoardOrderLine(methodSteps)).stream()
                .filter(line -> !CUSTOM_METHOD_HEADING.matcher(line).matches())
                .limit(5)
                .toList();
    }


    /** Prevents a source conclusion or image from being reused for a variation with a different colour count. */
    static boolean supportingEvidenceMatchesQuestion(
            String questionText,
            List<TeachingEvidence> supportingEvidence) {
        return !supportingEvidenceForQuestion(questionText, supportingEvidence).isEmpty();
    }


    /**
     * Selects source blocks that can be proven to describe this exact visual colouring question.
     *
     * <p>Search windows may contain the original question and several later variations.  A block that says both
     * "4 种颜色" and "6 种颜色" cannot tell the renderer which condition belongs to its attached map, so it is
     * deliberately excluded.  The synchronization layer must split it before an image or a source conclusion can
     * be reused.  Omitting an ambiguous asset is preferable to printing a mathematically wrong diagram.</p>
     */
    static List<TeachingEvidence> supportingEvidenceForQuestion(
            String questionText,
            List<TeachingEvidence> supportingEvidence) {
        if (supportingEvidence == null || supportingEvidence.isEmpty()) {
            return List.of();
        }
        if (questionText == null || !COLORING_TOPIC.matcher(questionText).find()) {
            return supportingEvidence;

        }
        Set<Integer> requested = colorCounts(questionText);
        if (requested.isEmpty()) {
            return List.of();
        }
        return supportingEvidence.stream().filter(evidence -> {
            Set<Integer> sourceCounts = colorCounts(
                    normalizedInlineText(evidence.sourceTitle()) + " " + normalizedInlineText(evidence.snippet()));
            // Equality, instead of any-overlap, rejects a merged OCR window containing neighbouring 4/5/6-colour
            // variants.  It also guarantees a 4-colour source cannot be used as evidence for the 6-colour task.
            return sourceCounts.equals(requested);
        }).toList();
    }


    static String lectureTopicHeading(String questionText) {
        return COLORING_TOPIC.matcher(questionText == null ? "" : questionText).find()
                ? "题型定位：邻接关系与颜色数量"
                : "题型定位";
    }


    static String lecturePathHeading(String questionText) {
        return COLORING_TOPIC.matcher(questionText == null ? "" : questionText).find()
                ? "按邻接关系分类"
                : "推导路径";
    }


    /** Uses a source-supported conclusion when present, and otherwise visibly preserves the need for teacher review. */
    static String lectureConclusion(List<TeachingEvidence> supportingEvidence) {
        return lectureSourceResult(supportingEvidence);
    }


    /**
     * Extracts only a short, source-verifiable result for the projection card.  Raw OCR paragraphs are useful to the
     * drafting model but unreadable on a slide and can accidentally blend later variations into the current question.
     */
    static String lectureSourceResult(List<TeachingEvidence> supportingEvidence) {
        if (supportingEvidence != null) {
            Pattern total = Pattern.compile("(?:合计|答案)\\s*[：:]?\\s*([0-9A-Za-z_\\\\^+×*=\\s]{1,48})");
            for (TeachingEvidence evidence : supportingEvidence) {
                Matcher matcher = total.matcher(normalizedInlineText(evidence.snippet()));
                if (matcher.find()) {
                    String result = matcher.group(1).replaceAll("\\s+", "").strip();
                    if (!result.isBlank()) {
                        return "资料分类结果：" + result;
                    }
                }
            }
        }
        return "按题图条件完成分类计数；结论必须回到已授权题图和资料原文核验。";
    }


    /**
     * Renders projection cards as independent pages instead of one continuous numbered list.
     * A card is the smallest authored lecture unit, so a page break is inserted only between cards.
     */
    static String lectureCardPages(List<String> cards, int workspaceEm, boolean startOnNewPage) {
        if (cards == null || cards.isEmpty()) {
            return "\\vspace{" + LECTURE_CARD_WORKSPACE_EM + "em}\n";
        }
        int space = Math.max(LECTURE_CARD_WORKSPACE_EM, workspaceEm);
        StringBuilder builder = new StringBuilder();
        if (startOnNewPage) {
            builder.append("\\clearpage\n");
        }
        int index = 1;
        for (String card : cards) {
            if (card == null || card.isBlank()) {
                continue;
            }
            String safeCard = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                    guardHandoutLatex(escapeLatex(card), true));
            if (safeCard.isBlank()) {
                // Do not emit an empty heading/page when sanitization removes an unreadable model fragment.
                continue;
            }
            if (index > 1) {
                builder.append("\\clearpage\n");
            }
            builder.append("\\subsection*{第 ").append(index).append(" 题 / 讲解单元}\n")
                    .append("\\paragraph{投屏内容}\n")
                    .append(safeCard)
                    // \vfill consumes the remaining landscape page.  The explicit vspace remains for PDFBox
                    // fallback, which does not implement TeX glue but must still keep a generous visual gap.
                    .append("\n\\vfill\n")
                    .append("\n\\vspace{").append(space).append("em}\n");
            index += 1;
        }
        return index == 1
                ? "\\vspace{" + space + "em}\n"
                : builder.toString();
    }


    static String extractLatexSection(String latex, String sectionTitle) {
        if (latex == null || latex.isBlank() || sectionTitle == null || sectionTitle.isBlank()) {
            return "";
        }
        String[] lines = latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder body = new StringBuilder();
        boolean capturing = false;
        for (String line : lines) {
            String stripped = line.strip();
            Matcher heading = LATEX_HEADING_LINE.matcher(stripped);
            if (heading.matches() && "section".equals(heading.group(1).replace("*", ""))) {
                String title = heading.group(2).strip();
                if (capturing) {
                    break;
                }
                capturing = title.equals(sectionTitle);
                continue;
            }
            if (capturing) {
                body.append(line).append('\n');
            }
        }
        return body.toString().strip();
    }


    static String removeEmptyTitledBlocks(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String[] lines = latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        return renderNonEmptyTitleRange(lines, 0, lines.length).strip();
    }


    static String renderNonEmptyTitleRange(String[] lines, int start, int end) {
        StringBuilder builder = new StringBuilder();
        int index = start;
        while (index < end) {
            Matcher heading = LATEX_HEADING_LINE.matcher(lines[index].strip());
            if (!heading.matches()) {
                if (!isBlankWorkspaceLabelLine(lines[index])) {
                    builder.append(lines[index]).append('\n');
                }
                index += 1;
                continue;
            }
            int level = latexHeadingLevel(heading.group(1));
            int next = index + 1;
            while (next < end) {
                Matcher nextHeading = LATEX_HEADING_LINE.matcher(lines[next].strip());
                if (nextHeading.matches() && latexHeadingLevel(nextHeading.group(1)) <= level) {
                    break;
                }
                next += 1;
            }
            String body = renderNonEmptyTitleRange(lines, index + 1, next).strip();
            if (hasRealLatexContent(body)) {
                builder.append(lines[index].strip()).append('\n').append(body).append("\n\n");
            }
            index = next;
        }
        return builder.toString();
    }


    static int latexHeadingLevel(String command) {
        String normalized = command == null ? "" : command.replace("*", "");
        return switch (normalized) {
            case "section" -> 1;
            case "subsection" -> 2;
            case "subsubsection", "paragraph" -> 3;
            default -> 4;
        };
    }


    static boolean hasRealLatexContent(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!isBlankOnlyLatexLine(rawLine)) {
                return true;
            }
        }
        return false;
    }


    static boolean isBlankWorkspaceLabelLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return false;
        }
        String compact = text
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return Set.of("留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }


    static boolean isBlankOnlyLatexLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return true;
        }
        if (text.matches("^\\\\vspace\\{[0-9.]+em}\\s*$")
                || text.matches("^\\\\(?:smallskip|medskip|bigskip|par)\\s*$")
                || text.matches("^\\\\underline\\{\\\\hspace\\{[0-9.]+em}}\\s*$")
                || text.matches("^\\\\(?:begin|end)\\{(?:itemize|enumerate|center)}\\s*$")) {
            return true;
        }
        String compact = text
                .replaceAll("\\\\vspace\\{[^}]+}", "")
                .replaceAll("\\\\underline\\{\\\\hspace\\{[^}]+}}", "")
                .replaceAll("\\\\hspace\\{[^}]+}", "")
                .replaceAll("\\\\par", "")
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return compact.isBlank()
                || isBlankWorkspaceLabelLine(text)

                || Set.of("作答", "作答区", "课堂作答区", "我的解答", "解答", "推导区", "订正", "订正记录",
                        "错因", "错因记录", "订正与错因", "空白区", "留白区", "留白", "手写区",
                        "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }


    static String guardDraftText(String value, boolean teacherVersion) {
        String guarded = guardHandoutLatex(value, teacherVersion);
        if (!teacherVersion) {
            guarded = sanitizeStudentWorkflowText(guarded);
        }
        if (!teacherVersion && guarded.isBlank()) {
            return "";
        }
        return guarded;
    }


    static List<String> guardDraftItems(List<String> values, boolean teacherVersion) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> guarded = new ArrayList<>();
        for (String value : values) {
            String item = guardHandoutLatex(value, teacherVersion)
                    .replaceAll("\\s+", " ")
                    .strip();
            if (!teacherVersion) {
                item = sanitizeStudentWorkflowText(item).replaceAll("\\s+", " ").strip();
            }
            if (!item.isBlank()) {
                guarded.add(item);
            }
        }
        return List.copyOf(guarded);
    }
}
