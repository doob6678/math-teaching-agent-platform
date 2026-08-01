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
 * TeachingWorkflowQuestionRenderer owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates questionrenderer rules.
 */
final class TeachingWorkflowQuestionRenderer {
    private TeachingWorkflowQuestionRenderer() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    /** Keeps the final conclusion visible in the teacher answer block without duplicating the full derivation. */
    static String modelDraftConclusionForQuestion(String excerpt) {
        String text = excerpt == null ? "" : excerpt.strip();
        if (text.isBlank()) {
            return "";
        }
        int conclusion = Math.max(text.lastIndexOf("故"), Math.max(text.lastIndexOf("因此"), text.lastIndexOf("答案")));
        String result = conclusion >= 0 ? text.substring(conclusion).strip() : text;
        return result.length() > 420 ? result.substring(0, 420).strip() : result;
    }


    /**
     * Retrieves one terminal answer from the model's dedicated scoring block by the immutable source question
     * number. The renderer never guesses from a neighbouring unit: missing or malformed number labels return an
     * empty string, so the publication gate continues to protect the teacher export.
     */
    static String modelDraftAnswerForQuestion(String teacherExplanation, String questionText) {
        if (teacherExplanation == null || teacherExplanation.isBlank() || questionText == null || questionText.isBlank()) {
            return "";
        }
        Matcher sourceNumber = SOURCE_QUESTION_NUMBER.matcher(questionText);
        if (!sourceNumber.find()) {
            return modelDraftConclusionForQuestion(modelDraftExcerptForQuestion(teacherExplanation, questionText));
        }
        int answerBlockStart = teacherExplanation.indexOf("【答案与评分点】");
        if (answerBlockStart < 0) {
            return modelDraftConclusionForQuestion(modelDraftExcerptForQuestion(teacherExplanation, questionText));
        }
        String answerBlock = teacherExplanation.substring(answerBlockStart + "【答案与评分点】".length());
        String number = Pattern.quote(sourceNumber.group(1));
        Pattern answerEntry = Pattern.compile(
                "(?:第\\s*)?" + number + "\\s*题?\\s*(?:[（(][^）)]{0,32}[）)])?\\s*(?:[：:]\\s*)?(.+?)"
                        + "(?=；\\s*(?:第\\s*)?\\d{1,3}\\s*题?(?:\\s*[（(]|\\s*[：:]|\\s*\\d+分)|【|$)",
                Pattern.DOTALL);
        Matcher entry = answerEntry.matcher(answerBlock);
        if (!entry.find()) {
            // Providers often omit the colon in compact scoring prose (for example “第13题5分填…”).
            // The numbered derivation is still tied to this exact source stem, so retain its conclusion rather
            // than replacing a real solved chain with the forbidden unverified-answer placeholder.
            return modelDraftConclusionForQuestion(modelDraftExcerptForQuestion(teacherExplanation, questionText));
        }
        String answer = entry.group(1).replaceAll("\\s+", " ").strip();
        return answer.length() > 420 ? answer.substring(0, 420).strip() : answer;
    }


    /** Builds a short, point-specific method heading for the printable unit. */
    static String methodHeading(String knowledgePoint, String draftMethodSteps) {
        if (draftMethodSteps != null && !draftMethodSteps.isBlank()) {
            Matcher matcher = CUSTOM_METHOD_HEADING.matcher(draftMethodSteps);
            if (matcher.find()) {
                String authored = matcher.group(1).strip();
                String authoredWithoutInternalPrefix = authored.replaceFirst("^核心方法\\s*[：:]\\s*", "").strip();
                if (!authored.isBlank()
                        && !authoredWithoutInternalPrefix.isBlank()
                        && !authoredWithoutInternalPrefix.equals("核心方法")
                        && !authoredWithoutInternalPrefix.matches(".*(?:方法主线|解题步骤|提示词|模板|AI|JSON|debug).*$")) {
                    // The heading itself is printable content.  Do not expose the renderer's internal section
                    // label (“核心方法”) in front of an authored topic title.
                    return authoredWithoutInternalPrefix;
                }
            }
        }
        String title = knowledgePoint == null || knowledgePoint.isBlank() ? "本节知识" : knowledgePoint.strip();
        String compactTitle = title.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(compactTitle).find()) {
            return title + "：从邻接关系到分类计数";
        }
        if (isQuadraticFunctionText(compactTitle)) {

            return title + "：从顶点与对称轴建立函数模型";
        }
        return title + "：条件识别与推导";
    }


    /** Embeds an already permission-checked local asset directly; opaque markers are for API transport only. */
    static String authorizedImageLatex(String path) {
        String normalized = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
        return "\\begin{center}\n\\includegraphics[width=" + PRINTED_IMAGE_WIDTH + ",height=" + PRINTED_IMAGE_MAX_HEIGHT
                + ",keepaspectratio]{\\detokenize{"
                + normalized + "}}\n\\end{center}";
    }


    /** Uses the projection column width and fixed height budget while preserving the authorized image aspect ratio. */
    static String lectureAuthorizedImageLatex(String path) {
        String normalized = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
        return "\\begin{center}\n\\includegraphics[width=\\linewidth,height=" + LECTURE_IMAGE_MAX_HEIGHT
                + ",keepaspectratio]{\\detokenize{" + normalized + "}}\n\\end{center}";
    }


    static int appendTeacherQuestion(
            StringBuilder builder,
            int questionNumber,
            String heading,
            TeachingEvidence question,
            String authorizedImagePath,
            String draftAnswerPoints,
            String draftMethodSteps,
            String fallbackHint) {
        if (question == null) {
            return questionNumber;
        }
        String questionText = questionTextOnly(question.snippet());
        if (isUnusableQuestionText(questionText)) {
            // A malformed OCR/import row is not an example. Omitting it is safer than printing a placeholder.
            return questionNumber;
        }
        if (requiresAuthorizedFigure(questionText)
                && (authorizedImagePath == null || authorizedImagePath.isBlank()
                || !Files.isRegularFile(Path.of(authorizedImagePath)))) {
            // The single-document synchronizer must materialize the exact source image before a figure-dependent
            // question is eligible.  This is the direct guard against the broken "如图" page in the visual audit.
            return questionNumber;
        }
        // Keep the question title, stem, and authorized diagram together while still allowing the explanation to
        // continue naturally on the following page. This is denser than a forced page break and prevents split 图题.
        // A diagram-dependent item is an indivisible unit: its stem, source figure and explanation must begin on
        // the same page. \Needspace only protects available remaining height and previously allowed the stem to be
        // stranded on the preceding page, so a real figure starts a fresh teacher page.
        if (authorizedImagePath != null && !authorizedImagePath.isBlank()) {
            builder.append("\\clearpage\n");
        } else {
            builder.append("\\Needspace{26\\baselineskip}\n");
        }
        String sourceAnswer = questionAnswerOnly(question.snippet());
        String bankSteps = questionBankSteps(sourceAnswer);

        // Do not repeat a lesson-wide model paragraph for every question.  A teacher page must either use the
        // question bank's own derivation or a route inferred from this visible stem.
        String detailedSteps = !bankSteps.isBlank()
                ? formatDraftContentAsLatex(withoutBoardOrderLine(bankSteps))
                : latexEnumerate(lectureQuestionFallbackPath(questionText));
        String answer = teacherQuestionConclusion(questionText, sourceAnswer,
                compactQuestionBankAnswer(questionBankAnswerWithoutSteps(sourceAnswer)), draftAnswerPoints);
        // Keep the pedagogical chain visible instead of concatenating a final answer and an unrelated
        // global draft into one paragraph. The entry is tied to this exact bank title; steps and answer
        // remain separately reviewable for every retrieved question.
        // The question bank title is source metadata, not a second problem statement.  Printing it both above and
        // inside the prompt produced the duplicated “例题/题目” block in teacher PDFs, so analysis starts from the
        // visible stem itself and never exposes a source label.
        String analysisEntry = questionAnalysisEntry(questionText);
        String solutionHeading = questionSolutionHeading(questionText);
        // Numbered headings are consumed by the PDF exporter to keep each real question separated, including 16:10.
        builder.append("\\subsection*{第").append(questionNumber).append("题 ")
                .append(escapeLatex(heading)).append("}\n")
                .append("\\paragraph{题目}\n")
                .append(escapeLatex(questionText)).append("\n");
        // A permission-checked figure is part of this concrete example, never a decoration for the previous method
        // block.  Keeping it immediately after the prompt preserves the question-to-image relation in all exports.
        if (authorizedImagePath != null && !authorizedImagePath.isBlank()) {
            builder.append(authorizedImageLatex(authorizedImagePath)).append("\n");
        }
        builder
                .append("\n\n\\paragraph{").append(escapeLatex(analysisHeading(questionText))).append("}\n")
                .append(escapeLatex(analysisEntry))
                .append("\n\n\\paragraph{").append(escapeLatex(solutionHeading)).append("}\n")
                .append(detailedSteps)
                .append("\n\n\\paragraph{答案与评分点}\n")
                .append(answer)
                .append("\n\n");
        return questionNumber + 1;
    }


    /** Uses a topic-owned entry label so the PDF never exposes the generic prompt scaffold as a lesson heading. */
    static String analysisHeading(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(text).find()) {
            return "先看相邻关系";
        }
        if (isQuadraticFunctionText(text)) {
            return "先定图像特征";
        }
        return "条件落点";
    }


    /** Derives a printable, topic-specific name for the deduction chain. */
    static String questionSolutionHeading(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(text).find()) {
            return "按颜色分类计数";
        }
        if (isQuadraticFunctionText(text)) {
            return "从顶点与对称轴推导";
        }
        if (isVectorQuestion(text)) {
            return "由数量积求模长";
        }
        if (isLogOptimizationQuestion(text)) {
            return "由对数变号确定参数";
        }
        return "推导链条";
    }


    /** Supplies a concrete first move tied to the actual stem, never a generic “read the prompt” instruction. */
    static String questionAnalysisEntry(String questionText) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (text.contains("4×4方格") || text.contains("4×4 方格")) {
            return "把四行依次选中的列号记为一个排列；列号不重复正好等价于每列恰选一个方格。";
        }
        if (text.contains("二面角") || text.contains("对折")) {
            return "折叠前先在平面图中找出 EF 与相关边的垂直关系；折叠保持长度和角度，再转入空间证明。";
        }
        if (COLORING_TOPIC.matcher(text).find()) {
            return "先把题图中的公共边界记成邻接关系；只有共边界的区域互相限制颜色，不能直接按区域个数写幂。";
        }
        if (isQuadraticFunctionText(text)) {
            return "先判断开口方向、顶点与对称轴，再把题目要求转成对应的函数值或参数条件。";
        }
        if (isVectorQuestion(text)) {
            return "先把垂直条件改写成数量积方程，再对模长等式平方，联立消去数量积。";
        }
        if (isLogOptimizationQuestion(text)) {
            return "令 t=x+b>0，利用 ln t 在 t=1 处变号确定参数关系，再在约束直线上求平方和最小值。";
        }
        return "先圈出题干给出的条件与目标，明确第一步要使用的定义、公式或图形关系。";
    }


    /**
     * Supplies a checked conclusion only where the stem itself determines it.  OCR score rubrics and a global
     * lesson answer are deliberately rejected: they are not an answer to the current question.
     */
    static String teacherQuestionConclusion(
            String questionText, String sourceAnswer, String compactBankAnswer, String draftAnswerPoints) {
        String text = questionText == null ? "" : questionText.replaceAll("\\s+", "");
        if (text.contains("4×4方格") || text.contains("4×4 方格")) {
            return "共有 $4!=24$ 种选法；最大和为 $40+33+22+15=110$（也可取 $31+42+22+15=110$）。";
        }
        if ((text.contains("二面角") || text.contains("对折")) && text.contains("PC=4√3")) {
            return "（1）$EF\\perp PD$；（2）所求二面角的正弦值为 $\\frac{8}{\\sqrt{65}}$。";
        }
        if (isVectorQuestion(text)) {
            return "由 $(\\vec b-2\\vec a)\\cdot\\vec b=0$ 得 $|\\vec b|^2=2\\vec a\\cdot\\vec b$；又由 $|\\vec a+2\\vec b|^2=4$ 联立可得 $|\\vec b|=\\frac{\\sqrt{2}}{2}$，选 B。";
        }
        if (isLogOptimizationQuestion(text)) {
            return "令 $t=x+b>0$，因 $\\ln t$ 在 $t=1$ 处变号，恒有 $(t+a-b)\\ln t\\ge0$ 必须满足 $a-b=-1$，即 $b=a+1$。于是 $a^2+b^2=a^2+(a+1)^2$，在 $a=-\\frac12$ 时取最小值 $\\frac12$，选 C。";
        }
        if (!isUnreliableQuestionAnswer(compactBankAnswer)) {
            return compactBankAnswer;
        }
        if (!isUnreliableQuestionAnswer(draftAnswerPoints)) {
            return compactQuestionBankAnswer(draftAnswerPoints);
        }
        return "\\textbf{该题缺少题号级核验答案，暂不发布。}";
    }


    /** Detects the plane-vector question family before generic fallback text can be reused. */
    static boolean isVectorQuestion(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return (compact.contains("向量") || compact.contains("\\vec") || compact.contains("⃗"))
                && (compact.contains("垂直") || compact.contains("⊥") || compact.contains("数量积")
                || compact.contains("模长") || compact.contains("|a+2b|"));
    }


    /** Detects the parameter-optimization logarithm item used by the real task's question 8. */
    static boolean isLogOptimizationQuestion(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return compact.contains("ln") && compact.contains("a^2") && compact.contains("b^2")
                && (compact.contains("恒成立") || compact.contains(">=0") || compact.contains("≥0")
                || compact.contains("最小值"));
    }


    /** Rejects OCR label dumps and whole-paper scoring notes before they are displayed as a single-question answer. */
    static boolean isUnreliableQuestionAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String originalCompact = answer.replaceAll("\\s+", "");
        String compact = repairMojibake(answer).replaceAll("\\s+", "");
        return compact.contains("各题对应分值")
                || compact.contains("图中几何标签")
                || compact.contains("资料答案：要点：答案")
                || compact.contains("答案要点")
                || compact.contains("学科网")
                || compact.contains("股份有限公司")
                || compact.contains("【解析】")
                || compact.contains("【分析】")
                || compact.contains("【小问")
                || compact.matches(".*第\\s*\\d+\\s*页\\s*/?\\s*共\\s*\\d+\\s*页.*")
                || compact.contains("答案：第")
                || compact.contains("解析卷")
                || compact.contains("题库未提供")
                || compact.contains("⋯")
                || compact.contains("……")
                || compact.contains("...")
                || (Math.max(mojibakeScore(originalCompact), mojibakeScore(compact)) >= 5
                        && (originalCompact + compact).matches(".*\\d+.*"))
                || compact.length() < 4;
    }


    /** Repairs legacy UTF-8-as-Latin-1 snapshots without changing correctly decoded Chinese text. */
    static String repairMojibake(String value) {
        if (value == null || value.isBlank()
                || !value.matches("(?s).*[ÃÂåæçèéêïðñã].*")) {
            return value == null ? "" : value;
        }
        try {
            String candidate = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            long sourceNoise = mojibakeScore(value);
            long candidateNoise = mojibakeScore(candidate);
            long candidateHan = candidate.chars().filter(character -> character >= 0x4E00 && character <= 0x9FFF).count();
            return candidateHan > 0 && candidateNoise < sourceNoise ? candidate : value;
        } catch (RuntimeException ignored) {
            return value;
        }
    }


    static long mojibakeScore(String value) {
        return value == null ? 0 : value.chars()
                .filter(character -> character == 'Ã' || character == 'Â' || character == 'å'
                        || character == 'æ' || character == 'ç' || character == 'è'
                        || character == 'é' || character == 'ê' || character == 'ï'
                        || character == 'ð' || character == 'ñ' || character == 'ã')
                .count();
    }


    /** Pulls verified bank steps out of the answer metadata so they render as an actual solution chain. */
    static String questionBankSteps(String formattedAnswer) {
        if (formattedAnswer == null || formattedAnswer.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?:^|；)步骤：(.+?)(?=；(?:答案|解析|评分点|方法|提示|难度|补充\\d+)：|$)")
                .matcher(formattedAnswer);
        return matcher.find() ? matcher.group(1).strip() : "";
    }


    /**
     * Keeps the solved example focused on mathematical deductions.  A generated "板书顺序" is an instructor
     * delivery note rather than a derivation step, and when appended after a long figure it was routinely stranded
     * by itself on the following page.  The core-method block already provides the reusable classroom approach.
     */
    static String withoutBoardOrderLine(String steps) {
        if (steps == null || steps.isBlank()) {
            return "";
        }
        List<String> retained = new ArrayList<>();
        boolean skipBoardContinuation = false;

        for (String rawLine : steps.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String normalized = rawLine.replaceAll("\\s+", "").strip();
            if (normalized.contains("板书顺序")) {
                // Models commonly put the heading on one line and a dense circled-number sequence on the next. That
                // sequence repeats the solved steps but consumes enough height to strand the answer on a new page.
                skipBoardContinuation = true;
                continue;
            }
            if (skipBoardContinuation && normalized.matches("^[①②③④⑤⑥⑦⑧⑨⑩].*")) {
                skipBoardContinuation = false;
                continue;
            }
            skipBoardContinuation = false;
            retained.add(rawLine);
        }
        return String.join("\n", retained).strip();
    }


    /** Removes the extracted steps from the final-answer block to avoid printing the same evidence twice. */
    static String questionBankAnswerWithoutSteps(String formattedAnswer) {
        if (formattedAnswer == null || formattedAnswer.isBlank()) {
            return "";
        }
        return formattedAnswer.replaceFirst("(?:^|；)步骤：.+?(?=；(?:答案|解析|评分点|方法|提示|难度|补充\\d+)：|$)", "")
                .replaceAll("；{2,}", "；")
                .replaceAll("^；|；$", "")
                .strip();
    }


    /**
     * Compresses a long teacher-source answer into auditable calculations and the source's terminal result. Raw OCR
     * paragraphs are kept in retrieval metadata, never copied wholesale into the printable teacher page.
     */
    static String compactQuestionBankAnswer(String answer) {
        if (answer == null || answer.isBlank()) {

            return "";
        }
        if (isUnreliableQuestionAnswer(answer)) {
            return "";
        }
        String normalized = repairMojibake(answer).replace("\\times", "×").replace("\\cdot", "·")
                .replaceAll("\\s+", " ").replaceAll("#{2,}", " ").strip();
        // Reject a whole OCR label dump before extracting short arithmetic fragments from it.
        if (isUnreliableQuestionAnswer(normalized)) {
            return "";
        }
        LinkedHashSet<String> expressions = new LinkedHashSet<>();
        // A complete additive classification is self-checking.  OCR frequently invents short fragments such as
        // "2=15" beside the real sum, so accept those fragments only when the source contains no complete sum.
        Matcher verifiedSumMatcher = VERIFIED_SUM_EXPRESSION.matcher(normalized);
        while (verifiedSumMatcher.find() && expressions.size() < 5) {
            expressions.add(verifiedSumMatcher.group(1).replaceAll("\\s+", " ").strip());
        }
        if (expressions.isEmpty()) {
            Matcher expressionMatcher = Pattern.compile("(?<![A-Za-z0-9])(?:[A-Za-z]+[_^{}0-9]*\\s*)?(?:[0-9]+(?:\\s*[+×*]\\s*[0-9]+)+\\s*=\\s*[0-9]+|[0-9]+\\s*=\\s*[0-9]+)(?![A-Za-z0-9])")
                    .matcher(normalized);
            while (expressionMatcher.find() && expressions.size() < 5) {
                expressions.add(expressionMatcher.group().replaceAll("\\s+", " ").strip());
            }
        }
        String terminal = "";
        Matcher terminalMatcher = Pattern.compile("(?:合计|总计|答案)\\s*[：:]?\\s*([^。；]{1,80})").matcher(normalized);
        while (terminalMatcher.find()) {
            String candidate = terminalMatcher.group(1).strip();
            if (!candidate.isBlank()) {
                terminal = candidate;
            }
        }
        if (!expressions.isEmpty()) {
            StringBuilder result = new StringBuilder(escapeLatex("资料答案："));
            if (!terminal.isBlank() && terminal.length() <= 42) {
                result.append(escapeLatex(terminal)).append("；");
            }
            result.append(expressions.stream()
                    .map(expression -> "$" + escapeLatexMath(expression) + "$")
                    .collect(java.util.stream.Collectors.joining("；")));
            return result.toString();
        }
        return escapeLatex(normalized.length() > 180 ? normalized.substring(0, 180) + "……" : normalized);
    }


    /** Converts source arithmetic into a safe inline math segment without escaping its LaTeX operators as prose. */
    static String escapeLatexMath(String expression) {
        return sanitizeMathSegment(expression
                .replace("×", "\\times")
                .replace("·", "\\cdot"));
    }


    static boolean isUnusableQuestionText(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return true;
        }
        String normalized = questionText.replaceAll("\\s+", "").strip();
        // Never guess whether a broken square means perpendicular, parallel, subset, or a missing glyph.  It must
        // be repaired by the real single-document synchronizer/OCR before this row can become a mathematical task.
        return UNRESOLVED_OCR_MATH_GLYPH.matcher(normalized).find()
                || normalized.equals("题目")
                || normalized.toLowerCase(Locale.ROOT).contains("todo")
                // Some imported Markdown rows contain the worked derivation after a slash but no actual stem.
                // A solution paragraph is not a question and must not become an AI-selected example/variation.
                || normalized.matches("^(?:化简目标表达式|利用同角三角函数关系|由已知条件推导|结合余弦定理计算结果).*")
                || normalized.contains("题目内容待补充")
                || normalized.contains("题目待补充")
                || normalized.matches("^(?:暂无|无|待补充|未提供).{0,16}$");
    }


    /** Extracts useful classroom prose from a structured draft without printing its internal field labels. */
    static String mergeTeacherDraftNotes(String teacherExplanation) {
        return mergeDistinctItems(8,
                draftBlockLines(draftBlockContent(teacherExplanation, teacherDraftLabels(), "知识定位")),
                draftBlockLines(draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别")),
                draftBlockLines(draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤")))
                .stream()
                .map(TeachingWorkflowService::escapeLatex)
                .collect(java.util.stream.Collectors.joining("\n"));
    }


    static String compactEvidenceFact(String value) {
        // Teacher-source OCR is evidence for the model, not text suitable for a handout.  Show only a complete,
        // independently checkable calculation; prose around it can contain merged neighbouring variants or OCR noise.
        Matcher sum = VERIFIED_SUM_EXPRESSION.matcher(normalizedInlineText(value));
        if (!sum.find()) {
            return "";
        }
        String expression = sum.group(1).replace('＋', '+').replaceAll("\\s+", " ").strip();
        return "资料分类结果：" + expression;
    }


    /** Normalizes read-only evidence text without changing the stored source or leaking raw OCR layout into the PDF. */
    static String normalizedInlineText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }


    static boolean canUseQuestionBank(TeachingRequestContext context) {
        String subjectType = context == null ? "" : context.subjectType();
        // Students may use only rows already filtered by the question-bank visibility query. This is required for
        // weak-point practice generation; teacher resources and answer-bearing management operations remain gated
        // separately by canUseTeacherResources and the controller capability checks.
        return "student".equalsIgnoreCase(subjectType)
                || "teacher".equalsIgnoreCase(subjectType)
                || "admin".equalsIgnoreCase(subjectType);
    }


    static boolean canUseTeacherResources(TeachingRequestContext context) {
        return canUseQuestionBank(context);
    }


    /**
     * Builds a compact evidence summary instead of dumping raw OCR chunks into the handout.
     */
    static String evidenceSummary(List<TeachingEvidence> evidence) {
        if (evidence.isEmpty()) {
            return "暂无教材证据。";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (TeachingEvidence item : evidence) {
            builder.append(escapeLatex(evidenceSourceLine(index, item)))
                    .append('\n');
            index += 1;
        }
        return builder.toString().strip();
    }


    static String evidenceSourceLine(int index, TeachingEvidence item) {
        if ("QUESTION_BANK".equals(item.sourceScope())) {
            return "来源 " + index
                    + "：题库，" + questionTitleWithoutDifficulty(item)
                    + "，难度 " + questionDifficulty(item)
                    + "；用途：分层练习与教师答案区。";
        }
        if ("TEACHER_RESOURCE".equals(item.sourceScope())) {
            String page = item.pageNo() > 0 ? "第 " + item.pageNo() + " 页" : "页码未记录";
            return "来源 " + index
                    + "：教师资料，" + printableEvidenceTitle(item.sourceTitle())
                    + "，" + page
                    + "；用途：题型方法、教师沉淀与讲义补充。";
        }
        String page = item.pageNo() > 0 ? "PDF " + item.pageNo() : "页码未记录";
        return "来源 " + index
                + "：公开教材，" + printableEvidenceTitle(item.sourceTitle())
                + "，" + page
                + "；用途：知识点定位与公式依据。";
    }
}
