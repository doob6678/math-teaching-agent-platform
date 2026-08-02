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
 * TeachingWorkflowDraftRenderer owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates draftrenderer rules.
 */
final class TeachingWorkflowDraftRenderer {
    private TeachingWorkflowDraftRenderer() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    /**
     * Teaching task responses must not fabricate ReAct traces before the backend owns a real tool-execution trace.
     */
    static List<TeachingReactStep> buildReactTrace(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            StudentMemoryResponse memoryResponse) {
        return List.of();
    }


    /**
     * 生成 LaTeX 讲义草稿；当前阶段输出结构，后续会接入更强的排版和 PDF 渲染。
     */
    static String buildTeacherHandoutLatex(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence,
            List<TeachingKnowledgePointPack> knowledgePointPacks,
            StudentMemoryResponse memoryResponse,
            TeachingHandoutTemplateProfile template,
            TeachingTaskResponse.AiDraft aiDraft,
            TeachingDraftSections draftSections) {
        String teacherExplanation = draftSections == null ? "" : draftSections.teacherExplanation();
        /*
         * Some providers compact a whole structured Chinese draft onto one physical line. The broad line-oriented
         * safety cleaner then correctly refuses that line if it contains any control-looking token, but it also
         * removes every valid worked example. For the teacher renderer we may recover only the already structured,
         * task-owned model field: per-question extraction and the later LaTeX/export filters still remove protocol
         * text, and student output never reads this fallback. This preserves real reasoning instead of emitting the
         * forbidden "题库未提供答案" placeholder.
         */
        if (teacherExplanation.isBlank() && aiDraft != null && aiDraft.structured()
                && aiDraft.teacherExplanation() != null && !aiDraft.teacherExplanation().isBlank()) {
            teacherExplanation = aiDraft.teacherExplanation().strip();
        }
        String teachingNotes = mergeTeacherDraftNotes(teacherExplanation);
        String draftKnowledgePosition = draftBlockContent(teacherExplanation, teacherDraftLabels(), "知识定位");
        String draftQuestionType = draftBlockContent(teacherExplanation, teacherDraftLabels(), "题型识别");
        String draftMethodSteps = draftBlockContent(teacherExplanation, teacherDraftLabels(), "方法步骤");
        String answerPoints = draftBlockContent(teacherExplanation, teacherDraftLabels(), "答案与评分点");
        String draftPitfalls = draftBlockContent(teacherExplanation, teacherDraftLabels(), "易错提醒");
        StringBuilder builder = new StringBuilder();
        List<TeachingKnowledgePointPack> packs = knowledgePointPacks == null || knowledgePointPacks.isEmpty()
                ? fallbackKnowledgePointPacks(request, evidence) : knowledgePointPacks;
        boolean zhaoMaster = isZhaoMasterTemplate(template);
        if (!zhaoMaster) {
            // The standard template retains an orientation section. The Zhao master intentionally starts from the
            // first verified question, matching its continuous exercise-page design and avoiding empty scaffolding.
            builder.append("\\section{").append(escapeLatex(lessonOpeningHeading(packs))).append("}\n")
                    .append(latexItemize(packs.stream()
                            .map(pack -> "掌握“" + pack.title() + "”的定义、条件识别与基本题型。")
                            .toList()))
                    .append("\n");
        }
        if (!zhaoMaster && isQuadraticFunctionTopic(request)) {
            // This is a real TikZ-rendered reference graph, not a textual placeholder.  It uses the canonical
            // y=x^2 curve because a topic-only request has no user function to plot; labels identify only invariant
            // geometric facts (vertex and symmetry axis), so the renderer cannot silently mark a wrong function.
            builder.append(quadraticReferenceGraph()).append("\n");
        }
        int questionNumber = 1;
        // The live draft is authored from the whole retrieval set. It is safe to enrich one-point lessons, but
        // reusing it under every sibling point can attach a correct explanation to the wrong question. For a
        // multi-point handout the renderer therefore keeps each bank item's own verified answer and source facts
        // until the structured per-question draft contract supplies a matching explanation.
        boolean allowGlobalDraftForQuestion = packs.size() == 1;
        for (TeachingKnowledgePointPack pack : packs) {
            // Keep the original structured response for per-question extraction. mergeTeacherDraftNotes intentionally
            // removes headings for printable summaries, so passing it here previously erased the model's boundaries
            // and forced the unverified “题库未提供答案” fallback below every real question.
            questionNumber = appendTeacherKnowledgePoint(builder, pack, questionNumber, teacherExplanation,
                    draftBlockContent(teacherExplanation, teacherDraftLabels(), "例题详解"),
                    draftKnowledgePosition, draftQuestionType, draftMethodSteps, answerPoints, draftPitfalls, aiDraft,
                    safeQuestionText(request), allowGlobalDraftForQuestion, zhaoMaster);
        }
        return builder.toString();
    }


    /** Keeps the master decision independent from mutable display names and never passes template metadata to a PDF. */
    static boolean isZhaoMasterTemplate(TeachingHandoutTemplateProfile template) {
        return template != null
                && template.summary() != null
                && ZHAO_MASTER_TEMPLATE_CODE.equals(template.summary().templateCode());
    }


    /** Derives a printable opening heading from the first verified knowledge point without exposing an internal label. */
    static String learningGoalHeading(List<TeachingKnowledgePointPack> packs) {
        if (packs == null || packs.isEmpty() || packs.getFirst().title() == null || packs.getFirst().title().isBlank()) {
            return "学习目标";
        }
        return packs.getFirst().title().strip() + "：学习目标";
    }


    /** Names the opening block from the verified topic instead of exposing a fixed template heading. */
    static String lessonOpeningHeading(List<TeachingKnowledgePointPack> packs) {
        String title = packs == null || packs.isEmpty() ? "本讲内容" : packs.getFirst().title();
        String compact = title == null ? "" : title.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(compact).find()) {
            return "涂色分类计数：题型总览";
        }
        if (isQuadraticFunctionText(compact)) {
            return "二次函数：图像与最值";
        }
        return (title == null || title.isBlank() ? "本讲内容" : title.strip()) + "：题型总览";
    }


    /** Uses the Zhao question-type tab language while keeping the actual label topic-owned. */
    static String topicSectionHeading(String title) {
        String safe = title == null || title.isBlank() ? "本讲题型" : title.strip();
        String compact = safe.replaceAll("\\s+", "");
        if (COLORING_TOPIC.matcher(compact).find()) {
            return "题型：涂色分类计数";
        }
        if (isQuadraticFunctionText(compact)) {
            return "题型：二次函数图像与最值";
        }
        return "题型：" + safe;
    }


    /** Detects quadratic-function lessons from the user request before adding the canonical graph. */
    static boolean isQuadraticFunctionTopic(TeachingTaskRequest request) {
        String text = (request == null ? "" : (request.learningGoal() == null ? "" : request.learningGoal()) + " " + safeQuestionText(request))
                .replaceAll("\\s+", "");
        return isQuadraticFunctionText(text);
    }



    static boolean isQuadraticFunctionText(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("二次函数") || normalized.contains("抛物线")
                || normalized.contains("顶点") || normalized.contains("对称轴");
    }


    /** Returns a deterministic, compilable TikZ graph whose marked features are mathematically exact. */
    static String quadraticReferenceGraph() {
        return """
                \\begin{tikzpicture}[x=0.95cm,y=0.65cm]
                \\draw[->,HandoutBorder] (-3.2,0) -- (3.2,0) node[right] {$x$};
                \\draw[->,HandoutBorder] (0,-0.6) -- (0,5.4) node[above] {$y$};
                \\draw[HandoutAccent,line width=1.1pt,domain=-3:3,samples=100] plot (\\x,{0.5*\\x*\\x});

                \\draw[HandoutBorder,dashed] (0,0) -- (0,5.0);
                \\fill[HandoutAccent] (0,0) circle (2pt) node[below right] {$V(0,0)$};
                \\node[HandoutBorder] at (1.8,4.7) {$y=x^2/2$};
                \\end{tikzpicture}
                """;
    }


    /**
     * Groups verified question-bank items by a concrete curriculum title before any printable text is generated.
     * Teacher-resource and textbook hits are attached only when their title or snippet mentions the same point.
     */
    static List<TeachingKnowledgePointPack> buildKnowledgePointPacks(
            TeachingTaskRequest request,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            List<TeachingEvidence> questionEvidence) {
        Map<String, List<TeachingEvidence>> questionsByPoint = new LinkedHashMap<>();
        for (TeachingEvidence question : questionBankEvidence(questionEvidence)) {
            String title = knowledgePointTitleForQuestion(question, textbookEvidence, teacherResourceEvidence, request);
            questionsByPoint.computeIfAbsent(title, ignored -> new ArrayList<>()).add(question);
        }
        List<TeachingKnowledgePointPack> packs = new ArrayList<>();
        for (Map.Entry<String, List<TeachingEvidence>> entry : questionsByPoint.entrySet()) {
            List<TeachingEvidence> questions = entry.getValue();
            TeachingEvidence workedExample = questions.isEmpty() ? null : questions.getFirst();
            TeachingEvidence variation = questions.size() > 1 ? questions.get(1) : null;
            List<TeachingEvidence> additionalVariations = questions.size() > 2
                    ? questions.subList(2, questions.size())
                    : List.of();
            List<TeachingEvidence> supporting = supportingEvidenceForPoint(
                    entry.getKey(), textbookEvidence, teacherResourceEvidence);
            if (supporting.isEmpty()) {
                // A question-bank title can be an OCR-derived sentence rather than the curriculum label.  When the
                // exact point cannot be matched, retain only teacher/textbook evidence that independently contains
                // a concrete term from the user's request; this is what carries the authorized figure into the
                // question unit without allowing an unrelated image to leak into the PDF.
                supporting = requestTopicSupportingEvidence(request, textbookEvidence, teacherResourceEvidence);
            }
            /*
             * A page-backed atomic question owns its diagram.  Earlier pack construction kept only textbook/teacher
             * supporting hits, so a correctly materialized QUESTION_BANK figure disappeared before the renderer
             * asked firstAuthorizedImageForQuestion() and the two `如图` rows were silently omitted. Add only the
             * question rows that actually carry a permission-checked local image; downstream matching still compares
             * the current stem, so a sibling page image cannot cross onto another question.
             */
            List<TeachingEvidence> questionOwnedVisualEvidence = questions.stream()
                    .filter(question -> question.imagePath() != null && !question.imagePath().isBlank())
                    .toList();
            if (!questionOwnedVisualEvidence.isEmpty()) {
                List<TeachingEvidence> mergedSupporting = new ArrayList<>(supporting);
                for (TeachingEvidence visualQuestion : questionOwnedVisualEvidence) {
                    if (!mergedSupporting.contains(visualQuestion)) {
                        mergedSupporting.add(visualQuestion);
                    }
                }
                supporting = List.copyOf(mergedSupporting);
            }
            packs.add(new TeachingKnowledgePointPack(
                    entry.getKey(),
                    supporting,
                    workedExample,
                    variation,
                    additionalVariations));
        }
        return List.copyOf(packs);
    }


    /** Returns permission-checked evidence whose source text contains a specific request topic term. */
    static List<TeachingEvidence> requestTopicSupportingEvidence(
            TeachingTaskRequest request,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence) {
        List<String> candidates = QuestionBankSearchText.candidateQueries(
                        request.learningGoal(), request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 2 && term.length() <= 24)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .toList();
        List<String> explicit = explicitTopicCandidates(request, candidates);
        List<String> usableTerms = explicit.stream()
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .toList();
        List<String> finalUsableTerms = usableTerms.isEmpty()
                ? candidates.stream()
                    .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                    .toList()
                : usableTerms;
        return concatEvidence(textbookEvidence, teacherResourceEvidence).stream()
                .filter(item -> evidenceMatchesAnyTopicTerm(item, finalUsableTerms))
                .limit(2)
                .toList();
    }


    /** Supplies a printable fallback only when the authorized question bank has no usable atomic question. */
    static List<TeachingKnowledgePointPack> fallbackKnowledgePointPacks(
            TeachingTaskRequest request,
            List<TeachingEvidence> evidence) {
        List<TeachingEvidence> availableEvidence = evidence == null ? List.of() : evidence.stream()
                .filter(item -> evidenceRespectsColorCountConstraint(request, item))
                .toList();
        List<String> topicTerms = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 2 && term.length() <= 18)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .distinct()
                .toList();
        // A fallback has no atomic bank question to anchor it.  Never let a broad vector/textbook hit
        // choose its subject or picture: only sources that contain an explicit requested topic term may
        // become printable evidence. Teacher material wins tie-breaks because it is the authorized source
        // for its own figure, then public textbook is used when it is actually on-topic.
        List<TeachingEvidence> alignedEvidence = availableEvidence.stream()
                .filter(item -> evidenceMatchesAnyTopicTerm(item, topicTerms))
                .sorted(Comparator.comparingInt(TeachingWorkflowService::fallbackEvidencePriority))
                .toList();
        List<TeachingEvidence> supporting = deduplicateSupportingEvidence(
                alignedEvidence.isEmpty() ? availableEvidence : alignedEvidence);
        String title = supporting.stream()
                .filter(item -> "TEACHER_RESOURCE".equals(item.sourceScope()))
                .map(TeachingWorkflowService::pointTitleFromEvidence)
                .filter(value -> !value.isBlank())
                .findFirst()
                .or(() -> supporting.stream()
                .filter(item -> "PUBLIC_TEXTBOOK".equals(item.sourceScope()))
                .map(TeachingWorkflowService::pointTitleFromEvidence)
                .filter(value -> !value.isBlank())
                .findFirst())
                .orElseGet(() -> request.learningGoal() == null || request.learningGoal().isBlank()
                        ? "本节知识"
                        : request.learningGoal().strip());
        // The request text is context only. It is never promoted to a retrieval-evidence row or a source citation.
        // Without an atomic bank row the pack contains only the verified supporting source and no fabricated example.
        return List.of(new TeachingKnowledgePointPack(title, List.copyOf(supporting), null, null));
    }


    /** Matches a source to a requested curriculum term without trusting a broad retrieval score alone. */
    static boolean evidenceMatchesAnyTopicTerm(TeachingEvidence evidence, List<String> topicTerms) {
        if (evidence == null || topicTerms == null || topicTerms.isEmpty()) {
            return false;
        }
        String source = (normalizedInlineText(evidence.sourceTitle()) + " "
                + normalizedInlineText(evidence.snippet())).toLowerCase(Locale.ROOT);
        return topicTerms.stream().anyMatch(term -> source.contains(term.toLowerCase(Locale.ROOT)));
    }


    /** Gives teacher evidence precedence only after it has independently passed the topic-match guard. */
    static int fallbackEvidencePriority(TeachingEvidence evidence) {
        if (evidence == null) {
            return 2;
        }
        return "TEACHER_RESOURCE".equals(evidence.sourceScope()) ? 0
                : "PUBLIC_TEXTBOOK".equals(evidence.sourceScope()) ? 1 : 2;
    }


    static String knowledgePointTitleForQuestion(
            TeachingEvidence question,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence,
            TeachingTaskRequest request) {
        String searchable = (questionTitleWithoutDifficulty(question) + " " + questionTextOnly(question.snippet()))
                .toLowerCase(Locale.ROOT);
        // Prefer the user's concrete curriculum term over a source title. Source titles often contain labels such as
        // “作业1/三棱柱”, which are useful citations but are not knowledge-point headings for a generated lesson.
        List<String> requestedCandidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText());
        List<String> explicitCandidates = explicitTopicCandidates(request, requestedCandidates);
        String canonicalTopic = canonicalQuestionTopic(request);
        String requestPoint = canonicalTopic;
        if (requestPoint.isBlank() || !searchable.replaceAll("\\s+", "").contains(requestPoint.toLowerCase(Locale.ROOT))) {
            requestPoint = (explicitCandidates.isEmpty() ? requestedCandidates.stream() : explicitCandidates.stream())
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term) || noSpecificRequestPoint(request))
                .filter(term -> searchable.replaceAll("\\s+", "").contains(term.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        }
        if (!requestPoint.isBlank()) {
            return requestPoint;
        }
        for (TeachingEvidence source : concatEvidence(textbookEvidence, teacherResourceEvidence)) {
            String candidate = pointTitleFromEvidence(source);
            if (candidate.length() >= 2 && searchable.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        String title = questionTitleWithoutDifficulty(question)
                .replaceFirst("^(?:赵礼显数学|赵礼显|高考数学)\\s*", "")
                .split("[：:/／·\\-—]", 2)[0]
                .strip();
        if (!title.isBlank() && title.length() <= 24) {
            return title;
        }

        return request.learningGoal() == null || request.learningGoal().isBlank() ? "本节知识" : request.learningGoal().strip();
    }


    static boolean noSpecificRequestPoint(TeachingTaskRequest request) {
        List<String> candidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText());
        List<String> explicitCandidates = explicitTopicCandidates(request, candidates);
        return (explicitCandidates.isEmpty() ? candidates.stream() : explicitCandidates.stream())
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .noneMatch(term -> !BROAD_TOPIC_TERMS.contains(term));
    }


    static List<TeachingEvidence> supportingEvidenceForPoint(
            String point,
            List<TeachingEvidence> textbookEvidence,
            List<TeachingEvidence> teacherResourceEvidence) {
        return concatEvidence(textbookEvidence, teacherResourceEvidence).stream()
                // Sources and question-bank titles frequently use different separators or suffixes.  Requiring the
                // entire generated heading silently discards a real RAG hit such as “函数新概念精讲 / 定义域” for
                // “函数新概念：定义域判断”.  Bind only on the full heading or on enough independent, non-broad
                // curriculum terms; a single generic “函数”/“导数” match is never sufficient.
                .filter(item -> supportsKnowledgePoint(item, point))
                .limit(2)
                .toList();
    }


    /**
     * Decides whether an authorized textbook/teacher-resource block can ground one printable knowledge-point pack.
     * The full title remains the strongest match.  The fallback deliberately uses the shared curriculum vocabulary
     * instead of a fuzzy vector score: pack assembly happens after retrieval, so it must be deterministic and
     * auditable when one directory lesson contains several sibling points.
     */
    static boolean supportsKnowledgePoint(TeachingEvidence evidence, String point) {
        if (evidence == null || point == null || point.isBlank()) {
            return false;
        }
        String normalizedPoint = normalizedInlineText(point).toLowerCase(Locale.ROOT);
        String sourceText = (normalizedInlineText(evidence.sourceTitle()) + " "
                + normalizedInlineText(evidence.snippet())).toLowerCase(Locale.ROOT);
        if (sourceText.contains(normalizedPoint)) {
            return true;
        }
        List<String> specificTerms = QuestionBankSearchText.candidateQueries(point).stream()
                .map(String::strip)
                .map(term -> term.toLowerCase(Locale.ROOT))
                .filter(term -> term.length() >= 2 && term.length() <= 18)
                // The combined natural-language query is only for recall.  It is not a stable curriculum term for
                // exact source binding because punctuation and wording legitimately differ across documents.
                .filter(term -> term.matches("[\\p{IsHan}A-Za-z0-9]+"))
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .distinct()
                .toList();
        if (specificTerms.isEmpty()) {
            return false;
        }
        int requiredMatches = Math.min(MIN_DISTINCT_POINT_TERMS_FOR_FUZZY_SUPPORT, specificTerms.size());
        long matchedTerms = specificTerms.stream().filter(sourceText::contains).count();
        return matchedTerms >= requiredMatches;
    }


    static String pointTitleFromEvidence(TeachingEvidence item) {
        String source = normalizedInlineText(item == null ? "" : item.sourceTitle());
        if (source.isBlank()) {
            return "";
        }
        String[] segments = source.split("\\s*/\\s*");
        return segments[segments.length - 1]
                .replaceAll("(?:教材|讲义|专题)$", "")
                .strip();
    }


    /** Writes one full teacher-facing unit, keeping each real question adjacent to the knowledge point it assesses. */
    static int appendTeacherKnowledgePoint(
            StringBuilder builder,
            TeachingKnowledgePointPack pack,
            int questionNumber,
            String teachingNotes,
            String draftWorkedExample,
            String draftKnowledgePosition,
            String draftQuestionType,
            String draftMethodSteps,
            String draftAnswerPoints,
            String draftPitfalls,
            TeachingTaskResponse.AiDraft aiDraft,
            String userQuestion,
            boolean allowGlobalDraftForQuestion,
            boolean zhaoMaster) {
        if (!zhaoMaster) {
            builder.append("\\section{").append(escapeLatex(topicSectionHeading(pack.title()))).append("}\n");
        }
        List<String> methodFacts = new ArrayList<>();
        if (!pack.supportingEvidence().isEmpty()) {
            methodFacts.addAll(pack.supportingEvidence().stream()
                    .map(TeachingEvidence::snippet)
                    .map(TeachingWorkflowService::compactEvidenceFact)
                    .filter(value -> !value.isBlank())
                    .limit(2)
                    .toList());
        }
        methodFacts.addAll(draftBlockLines(draftKnowledgePosition));
        methodFacts.addAll(draftBlockLines(draftQuestionType));

        methodFacts.addAll(draftBlockLines(draftMethodSteps).stream()
                .filter(line -> !CUSTOM_METHOD_HEADING.matcher(line).matches())
                .toList());
        methodFacts = new ArrayList<>(mergeDistinctItems(6, methodFacts, List.of(
                "先锁定题目对应的定义、条件和分界点，写清楚为什么可以使用该知识点。",
                "沿着条件逐步变形或分类讨论，每一步保留等号成立的依据。",
                "最后回到题目要求检查定义域、范围和边界，避免只得到形式上的结果。")));
        // A controlled “核心方法” heading makes the lesson hierarchy scannable.  Its content still comes from the
        // verified point and evidence; model-provided template/control words are rejected by methodHeading.
        if (!zhaoMaster) {
            builder.append("\\subsection*{").append(escapeLatex(methodHeading(pack.title(), draftMethodSteps))).append("}\n")
                    .append(latexItemize(methodFacts))
                    .append("\n\n");
        } else if (!pack.supportingEvidence().isEmpty()) {
            // The master page stays dense, but a short source-grounded fact preserves the requested audit trail.
            String evidenceFact = compactEvidenceFact(pack.supportingEvidence().getFirst().snippet());
            if (!evidenceFact.isBlank()) {
                builder.append("\\paragraph{资料依据}\n")
                        .append(escapeLatex(evidenceFact)).append("\n\n");
            }
        }
        TeachingEvidence workedExample = pack.workedExample();
        String workedText = workedExample == null ? "" : questionTextOnly(workedExample.snippet());
        // The asset is deliberately not printed in the method block.  A figure is an item of the question statement
        // and must travel with the title and prompt.  The selector also rejects a mixed OCR window so an original
        // map cannot be silently attached to a neighbouring colour-count variation.
        String workedExampleText = workedExample == null ? "" : questionTextOnly(workedExample.snippet());
        // A page image belongs only to a figure-dependent stem. Attaching a nearby page to an ordinary complex-number
        // question misleads both the model and the learner, even if the asset itself is permission-checked.
        String workedExampleImagePath = requiresAuthorizedFigure(workedExampleText)
                // The imported atomic row is the strongest possible source-to-image binding. Prefer it before the
                // broader point evidence, which may legitimately be absent for a cross-topic real-paper question.
                ? firstExistingAuthorizedImagePath(workedExample)
                : "";
        if (workedExampleImagePath.isBlank() && requiresAuthorizedFigure(workedExampleText)) {
            workedExampleImagePath = firstAuthorizedImageForQuestion(workedExampleText, pack.supportingEvidence());
        }
        // A multi-topic handout must never copy one global explanation below every question.  The model is required
        // to name real source question numbers; use only the matching slice, otherwise leave the question for the
        // publication gate to reject rather than printing a plausible but unrelated solution.
        String questionScopedDraftSteps = modelDraftExcerptForQuestion(teachingNotes, workedExampleText);
        String questionScopedDraftAnswer = modelDraftAnswerForQuestion(teachingNotes, workedExampleText);
        if (questionScopedDraftSteps.isBlank() && allowGlobalDraftForQuestion) {
            questionScopedDraftSteps = draftMethodSteps;
            questionScopedDraftAnswer = draftAnswerPoints;
        }
        int nextQuestionNumber = appendTeacherQuestion(builder, questionNumber, "例题", workedExample, workedExampleImagePath,
                questionScopedDraftAnswer, questionScopedDraftSteps,
                "先指出题干对应的定义、公式或分类依据，再写出关键等式。\n");
        int variationIndex = 1;
        for (TeachingEvidence variation : pack.variations()) {
            String variationHeading = variationIndex == 1 ? "变式练习" : "拓展变式";
            String variationText = variation == null ? "" : questionTextOnly(variation.snippet());
            // A variation is still an independently sourced atomic question. If it says “如图”, it must carry its
            // own permission-checked page asset rather than inheriting the worked example's diagram or being omitted.
            String variationImagePath = requiresAuthorizedFigure(variationText)
                    ? firstExistingAuthorizedImagePath(variation)
                    : "";
            // Every real bank row has its own audited source number.  Reuse only the model unit and final answer
            // carrying that same number; the earlier renderer accidentally left variations empty, then printed the
            // forbidden unverified-answer placeholder despite a real per-question model draft being available.
            String variationDraftSteps = modelDraftExcerptForQuestion(teachingNotes, variationText);
            String variationDraftAnswer = modelDraftAnswerForQuestion(teachingNotes, variationText);
            nextQuestionNumber = appendTeacherQuestion(builder, nextQuestionNumber, variationHeading, variation,
                    variationImagePath, variationDraftAnswer, variationDraftSteps,
                    "保留主方法，重点检查条件变化后边界和分类是否需要调整。\n");
            variationIndex += 1;
        }
        if (workedExample == null && !teachingNotes.isBlank()) {
            builder.append("\\subsection*{讲解}\n").append(teachingNotes).append("\n\n");
        }
        if (workedExample == null && !draftWorkedExample.isBlank()) {
            builder.append("\\subsection*{示例}\n")
                    .append(formatDraftContentAsLatex(draftWorkedExample)).append("\n\n");
        }
        List<String> notices = zhaoMaster
                // The master is a continuous problem page. A trailing warning block can strand one bullet on an
                // otherwise empty next page; the concrete condition check remains embedded in the worked solution.
                ? List.of()
                : mergeDistinctItems(3,
                        draftBlockLines(draftPitfalls),
                        guardDraftItems(aiDraft == null ? List.of() : aiDraft.followUpQuestions(), true),
                        List.of("条件变化时先检查定义域、参数范围和分界点，再下结论。"));
        if (!notices.isEmpty()) {
            builder.append(zhaoMaster ? "\\paragraph{易错提醒}\n" : "\\subsection*{注意}\n")
                    .append(latexItemize(notices)).append("\n\n");
        }

        return nextQuestionNumber;
    }


    /**
     * Extracts the one model section that actually describes this source stem.
     *
     * <p>The drafting contract asks the model to preserve source numbers, but real providers occasionally renumber
     * a selected sequence from one.  We first use the exact source marker, then match the top-level solution prompt
     * by overlapping meaningful terms.  A weak match is rejected rather than borrowing a correct solution from a
     * neighbouring question.</p>
     */
    static String modelDraftExcerptForQuestion(String teacherExplanation, String questionText) {
        if (teacherExplanation == null || teacherExplanation.isBlank() || questionText == null || questionText.isBlank()) {
            return "";
        }
        List<ModelExplanationUnit> units = modelExplanationUnits(teacherExplanation);
        if (units.isEmpty()) {
            return "";
        }
        Matcher sourceNumber = SOURCE_QUESTION_NUMBER.matcher(questionText);
        if (sourceNumber.find()) {
            String expected = sourceNumber.group(1);
            for (ModelExplanationUnit unit : units) {
                if (expected.equals(unit.number())) {
                    // The imported source number is an audited document identity, unlike the model's optional
                    // local ordering. Once it matches exactly, retain the full unit even when OCR/LaTeX aliases
                    // prevent lexical term overlap; the unit is still subject to the teacher publication gate.
                    if (!unit.excerpt().isBlank()) {
                        return unit.excerpt();
                    }
                }
            }
        }
        return units.stream()
                .map(unit -> Map.entry(unit, promptMatchCount(questionText, unit.prompt())))
                .filter(entry -> entry.getValue() >= MIN_MODEL_PROMPT_MATCHES)
                .max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey().excerpt())
                .orElse("");
    }


    /** Rejects numbered labels that contain only an answer while preserving robust same-source-number matching. */
    static boolean hasSubstantiveNumberedReasoning(String excerpt) {
        String normalized = excerpt == null ? "" : excerpt.replaceAll("\\s+", " ").strip();
        return normalized.length() >= MIN_NUMBERED_REASONING_CHARACTERS
                && SUBSTANTIVE_REASONING_SIGNAL.matcher(normalized).find();
    }


    /** Splits only top-level solution headings and leaves mathematical numbered steps inside their owning unit. */
    static List<ModelExplanationUnit> modelExplanationUnits(String teacherExplanation) {
        // The same 1., 2. format is used by the model's earlier “题型识别” list.  Only the worked-example block
        // is a solution contract; starting there prevents an exact source number from selecting a generic hint.
        int detailedStart = teacherExplanation.indexOf("【例题详解】");
        String workedExamples = detailedStart >= 0 ? teacherExplanation.substring(detailedStart) : teacherExplanation;
        // The following answer block contains compact labels such as “题13（5分）”.  Those labels are not second
        // solution units; leaving them here made the first question absorb scoring text and could attach a later
        // answer to the wrong prompt. Final answers are parsed by modelDraftAnswerForQuestion from the dedicated
        // block below, while this method remains responsible only for derivation excerpts.
        int answerBlockStart = workedExamples.indexOf("【答案与评分点】");
        if (answerBlockStart >= 0) {
            workedExamples = workedExamples.substring(0, answerBlockStart);
        }
        // A model may return otherwise valid markdown with its worked-example labels compacted onto one line. Insert
        // a structural newline before the explicit “题N：” token only; derivation steps such as “1.” remain inside
        // their owning unit and therefore cannot be mistaken for a second source problem.
        workedExamples = INLINE_MODEL_EXPLANATION_HEADING.matcher(workedExamples).replaceAll("\n");
        Matcher matcher = MODEL_EXPLANATION_HEADING.matcher(workedExamples);
        List<ModelExplanationHeader> headers = new ArrayList<>();
        while (matcher.find()) {
            headers.add(new ModelExplanationHeader(matcher.group(1), matcher.group(2).strip(), matcher.start(), matcher.end()));
        }
        List<ModelExplanationUnit> units = new ArrayList<>();
        for (int index = 0; index < headers.size(); index += 1) {
            ModelExplanationHeader header = headers.get(index);
            int bodyEnd = index + 1 < headers.size() ? headers.get(index + 1).start() : workedExamples.length();
            // The heading echoes the source prompt. The visible question is already rendered by appendTeacherQuestion;
            // keeping it here made stray “题 1:” fragments appear inside the deduction paragraph and doubled the
            // stem. Only the model-authored reasoning after its heading belongs in this printable block.
            String excerpt = workedExamples.substring(header.end(), bodyEnd).strip();
            String number = header.number();
            String prompt = header.prompt();
            if (!prompt.isBlank() && excerpt.length() >= prompt.length()) {
                units.add(new ModelExplanationUnit(number, prompt, excerpt.length() > 1600
                        ? excerpt.substring(0, 1600).strip() : excerpt));
            }
        }
        return units;
    }


    /** Counts shared two-character runs plus ASCII/math terms, avoiding fragile equality on OCR punctuation. */
    static int promptMatchCount(String sourceQuestion, String modelPrompt) {
        Set<String> sourceTerms = promptMatchTerms(sourceQuestion);
        Set<String> promptTerms = promptMatchTerms(modelPrompt);
        sourceTerms.retainAll(promptTerms);
        return sourceTerms.size();
    }


    /** Produces bounded Chinese bigrams and alphanumeric symbols that remain stable after model rephrasing. */
    static Set<String> promptMatchTerms(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher chineseRun = Pattern.compile("[\\p{IsHan}]{2,}").matcher(normalized);
        while (chineseRun.find()) {
            String run = chineseRun.group();
            for (int index = 0; index + 1 < run.length(); index += 1) {
                terms.add(run.substring(index, index + 2));
            }
        }
        Matcher symbol = Pattern.compile("[a-zαβγθ][a-z0-9αβγθ_']{0,7}").matcher(normalized);
        while (symbol.find()) {
            terms.add(symbol.group());
        }
        return terms;
    }
}
