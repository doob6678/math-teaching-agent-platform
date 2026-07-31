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
 * TeachingWorkflowEvidencePolicy owns one cohesive part of the teaching workflow. The facade keeps the service contract,
 * while this component isolates evidencepolicy rules.
 */
final class TeachingWorkflowEvidencePolicy {
    private TeachingWorkflowEvidencePolicy() {
        // Static policy component: it deliberately owns no request or persistence state.
    }


    /**
     * Collapses mirrored imports of the same atomic question before grouping them into a lesson.
     *
     * <p>Question ids identify database rows, not mathematical prompts: a source's question page and its later
     * detailed-analysis page legitimately have different ids.  The normalized visible stem is therefore the
     * publishing identity.  When two rows match, keep the one with an official answer first, then the one carrying
     * a same-page asset; this preserves the richest auditable source without inventing any content.</p>
     */
    static List<QuestionBankItemResponse> deduplicateAtomicQuestionRows(
            Collection<QuestionBankItemResponse> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, QuestionBankItemResponse> unique = new LinkedHashMap<>();
        for (QuestionBankItemResponse candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String key = normalizedAtomicQuestionKey(candidate.questionText());
            if (key.isBlank()) {
                continue;
            }
            QuestionBankItemResponse existing = unique.get(key);
            if (existing == null || shouldPreferAtomicQuestion(candidate, existing)) {
                unique.put(key, candidate);
            }
        }
        return List.copyOf(unique.values());
    }


    /** Uses the printable stem so source titles, answer JSON, and OCR spacing cannot change duplicate identity. */
    static String normalizedAtomicQuestionKey(String questionText) {
        String stem = questionTextOnly(questionText);
        return normalizedInlineText(stem)
                // Text and analysis pages often differ only by harmless $...$ delimiters or a copied TeX slash.
                // These are formatting transport, not a distinct mathematical prompt, so exclude them from the
                // cross-page identity while retaining every visible operator and numeral.
                .replaceAll("[，。；：、（）()【】〔〕\\[\\]{}<>《》‘’“”\\-—_$\\\\\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }


    /** Prefers an official answer, then a source block that can still resolve its authorized page asset. */
    static boolean shouldPreferAtomicQuestion(
            QuestionBankItemResponse candidate,
            QuestionBankItemResponse existing) {
        boolean candidateHasAnswer = candidate.answerJson() != null && !candidate.answerJson().isBlank()
                && !"{}".equals(candidate.answerJson().strip());
        boolean existingHasAnswer = existing.answerJson() != null && !existing.answerJson().isBlank()
                && !"{}".equals(existing.answerJson().strip());
        if (candidateHasAnswer != existingHasAnswer) {
            return candidateHasAnswer;
        }
        boolean candidateHasSource = candidate.sourceBlockId() != null && !candidate.sourceBlockId().isBlank();
        boolean existingHasSource = existing.sourceBlockId() != null && !existing.sourceBlockId().isBlank();
        if (candidateHasSource != existingHasSource) {
            return candidateHasSource;
        }
        return candidate.questionId().compareTo(existing.questionId()) < 0;
    }


    /**
     * Enables the bounded all-atomic-bank pack for either an explicit multi-topic request or the selected long-form
     * real-question master. The latter has a ten-question publication floor, so retaining a one-topic retrieval cap
     * would make a valid, visible library impossible to publish even though all rows are already permission-checked.
     */
    static boolean requiresQualifiedQuestionCompilation(TeachingTaskRequest request) {
        // This gate is intentionally request-local: broadening a normal single-topic lesson would mix unrelated
        // questions, whereas an explicitly requested directory/compilation may safely use the visible atomic bank.
        String questionText = request == null || request.questionText() == null ? "" : request.questionText();
        String learningGoal = request == null || request.learningGoal() == null ? "" : request.learningGoal();
        String text = (questionText + " " + learningGoal).replaceAll("\\s+", "");
        String templateCode = request == null || request.handoutTemplateCode() == null ? "" : request.handoutTemplateCode();
        return ZHAO_MASTER_TEMPLATE_CODE.equals(templateCode)
                || text.contains("综合") || text.contains("题组") || text.contains("多个知识点") || text.contains("目录");
    }


    /**
     * Converts synchronized teacher titles into bounded question-bank queries.  Only readable topic titles become
     * queries; opaque document ids and full OCR paragraphs never reach the bank or the model context.
     */
    static List<String> curriculumPointQueries(
            TeachingTaskRequest request,
            List<TeachingEvidence> teacherResourceEvidence) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (teacherResourceEvidence != null) {
            for (TeachingEvidence evidence : teacherResourceEvidence) {
                String point = pointTitleFromEvidence(evidence);
                if (point.length() >= 2 && point.length() <= 32 && !TOPIC_GENERIC_TERMS.contains(point)) {
                    queries.add(point);
                }
            }
        }
        if (queries.isEmpty()) {
            queries.addAll(topicKeywords(request));
        }
        return List.copyOf(queries);
    }


    /**
     * Keeps the visible, permission-checked question-bank rows in ranking order.  The requested retrieval count is
     * preserved throughout generation so a source pack with 22 real questions is not silently reduced to twelve.
     */
    static List<QuestionBankItemResponse> selectQuestionsByKnowledgePoint(
            TeachingTaskRequest request,
            List<QuestionBankItemResponse> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return List.copyOf(candidates);
    }


    /**
     * Broad source-pack discovery may inspect several pages of the caller-requested result set.  Saturating prevents
     * integer overflow while the question-bank service remains the authoritative pagination safeguard.
     */
    static int compilationSearchLimit(TeachingTaskRequest request) {
        int requested = request == null ? MIN_QUALIFIED_HANDOUT_QUESTION_COUNT : request.evidenceLimit();
        if (requested > Integer.MAX_VALUE / QUESTION_BANK_COMPILATION_QUERY_MULTIPLIER) {
            return Integer.MAX_VALUE;
        }
        return Math.max(MIN_QUALIFIED_HANDOUT_QUESTION_COUNT,
                requested * QUESTION_BANK_COMPILATION_QUERY_MULTIPLIER);
    }




    static int questionDifficultyRank(QuestionBankItemResponse item) {
        String difficulty = item.difficulty() == null ? "" : item.difficulty();
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


    /**
     * Requires a concrete mathematical topic match before a browsable question-bank item becomes generation evidence.
     * This boundary deliberately prefers an empty evidence list over a broad lexical match such as “题型” or “最大值”.
     */
    static boolean hasSpecificQuestionTopicMatch(TeachingTaskRequest request, QuestionBankItemResponse question) {
        if (question == null) {
            return false;
        }
        String searchable = ((question.questionTitle() == null ? "" : question.questionTitle()) + " "
                + (question.questionText() == null ? "" : question.questionText()))
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        List<String> candidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        // If the request contains a concrete child point, a broad domain hit is insufficient. This is the guard that
        // stops a generic “三棱柱” row from being used for a “线面角” lesson while still allowing a domain-only query
        // such as “空间向量” to use its broad bank.
        List<String> explicitCandidates = explicitTopicCandidates(request, candidates);
        List<String> explicitSpecific = explicitCandidates.stream()
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .filter(term -> searchable.contains(term.toLowerCase(Locale.ROOT)))
                .toList();
        if (explicitCandidates.stream().anyMatch(term -> !BROAD_TOPIC_TERMS.contains(term))) {
            return !explicitSpecific.isEmpty();
        }
        return candidates.stream().anyMatch(term -> searchable.contains(term.toLowerCase(Locale.ROOT)));
    }


    /** Returns the most specific request term present in a bank row for per-point quota selection. */
    static String questionKnowledgePointKey(TeachingTaskRequest request, QuestionBankItemResponse question) {
        String searchable = ((question.questionTitle() == null ? "" : question.questionTitle()) + " "
                + (question.questionText() == null ? "" : question.questionText()))
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        String canonicalTopic = canonicalQuestionTopic(request);
        if (!canonicalTopic.isBlank() && searchable.contains(canonicalTopic.toLowerCase(Locale.ROOT))) {
            return canonicalTopic;
        }
        List<String> candidates = QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText());
        List<String> explicitCandidates = explicitTopicCandidates(request, candidates);
        return (explicitCandidates.isEmpty() ? candidates.stream() : explicitCandidates.stream())
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> searchable.contains(term.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length))
                .orElseGet(() -> primaryTopicKeyword(request));
    }


    /**
     * Returns a stable curriculum label for a question family whose source titles carry year/import suffixes.
     * Coloring questions are the first affected family: the original row is titled “2013年涂色问题”, while
     * synchronized variations append “-教师同步验收”.  The label is deliberately selected from the request and
     * only accepted when it is present in the row, so an unrelated bank row cannot be pulled into the group.
     */
    static String canonicalQuestionTopic(TeachingTaskRequest request) {
        String requestText = ((request == null || request.learningGoal() == null) ? ""
                : request.learningGoal()) + " "
                + ((request == null || request.questionText() == null) ? "" : request.questionText());
        if (COLORING_TOPIC.matcher(requestText).find()) {
            return "涂色问题";
        }
        return "";
    }


    /**
     * Keeps only terms literally present in the user's request. QuestionBankSearchText also returns domain expansions
     * (for example line-plane-angle for every “空间向量” query); those expansions are for recall, never for strict
     * topic validation or heading assignment.
     */
    static List<String> explicitTopicCandidates(TeachingTaskRequest request, List<String> candidates) {
        String requestText = ((request.learningGoal() == null ? "" : request.learningGoal()) + " "
                + (request.questionText() == null ? "" : request.questionText()))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", "")
                .toLowerCase(Locale.ROOT);
        return candidates.stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> requestText.contains(term.toLowerCase(Locale.ROOT)))
                .toList();
    }


    /**
     * Rejects source-page bundles that were incorrectly imported as a single question.  The detailed source remains
     * available for repair in the question bank, but it is unsafe to show its first OCR fragment as lesson evidence.
     */
    static boolean isAtomicQuestionBankItem(QuestionBankItemResponse question) {
        String text = question.questionText() == null ? "" : question.questionText().strip();
        if (text.isBlank() || text.length() > MAX_HANDOUT_QUESTION_TEXT_CHARACTERS
                || isUnusableQuestionText(questionTextOnly(text))) {
            return false;
        }
        long topLevelQuestionCount = TOP_LEVEL_QUESTION_MARKER.matcher(text).results().count();
        return topLevelQuestionCount <= MAX_TOP_LEVEL_QUESTION_MARKERS;
    }


    /**
     * Keeps a task's model evidence grounded in the complete permission-filtered block window rather than its UI
     * search snippet.  Long source blocks retain their opening context plus one result-bearing clause, so a source
     * such as “24+48=72” cannot be silently lost merely because the search match occurred at its question heading.
     */
    static String compactTeachingEvidence(String expandedEvidence, String snippetFallback) {
        String normalized = normalizedInlineText(
                expandedEvidence == null || expandedEvidence.isBlank() ? snippetFallback : expandedEvidence);
        if (normalized.length() <= MAX_TEACHING_EVIDENCE_CHARS) {
            return normalized;
        }
        String opening = normalized.substring(0, Math.min(TEACHING_EVIDENCE_INTRO_CHARS, normalized.length())).strip();
        String conclusion = sourceConclusionClause(normalized);
        if (conclusion.isBlank()) {
            return normalized.substring(0, MAX_TEACHING_EVIDENCE_CHARS).strip();
        }
        String boundedConclusion = conclusion.substring(0,
                Math.min(TEACHING_EVIDENCE_CONCLUSION_CHARS, conclusion.length())).strip();
        String merged = (opening + "；" + boundedConclusion).strip();
        return merged.length() <= MAX_TEACHING_EVIDENCE_CHARS
                ? merged
                : merged.substring(0, MAX_TEACHING_EVIDENCE_CHARS).strip();
    }


    /** Returns the final mathematical conclusion/method clause from a long authorized source block. */
    static String sourceConclusionClause(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int answerIndex = firstMarkerIndex(value, "答案", "合计");
        if (answerIndex >= 0) {
            return sourceEvidenceWindow(value, answerIndex);
        }
        int reasoningIndex = firstMarkerIndex(value, "因此", "所以", "故", "=");
        return reasoningIndex < 0 ? "" : sourceEvidenceWindow(value, reasoningIndex);
    }


    /** Finds the earliest relevant source marker, preserving source order instead of a later variation's answer. */
    static int firstMarkerIndex(String value, String... markers) {
        int result = -1;
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }


    /** Captures a bounded neighborhood around an answer marker when Markdown paragraphs lack sentence punctuation. */
    static String sourceEvidenceWindow(String value, int markerIndex) {
        // Preserve the equality immediately before “合计”, but begin close enough to the marker that the final
        // answer remains inside the strict model-evidence budget instead of being truncated after an intermediate 48.
        int initialStart = Math.max(0, markerIndex - TEACHING_EVIDENCE_MARKER_CONTEXT_CHARS);
        int punctuationStart = Math.max(
                Math.max(value.lastIndexOf('。', markerIndex), value.lastIndexOf('；', markerIndex)),
                Math.max(value.lastIndexOf('！', markerIndex), value.lastIndexOf('？', markerIndex)));
        int start = punctuationStart >= initialStart ? punctuationStart + 1 : initialStart;
        int boundedEnd = Math.min(value.length(), markerIndex + TEACHING_EVIDENCE_CONCLUSION_CHARS);
        int sentenceEnd = nextSentenceEnd(value, markerIndex);
        int end = sentenceEnd >= markerIndex && sentenceEnd < boundedEnd ? sentenceEnd + 1 : boundedEnd;
        return value.substring(start, end).strip();
    }


    /** Returns the next terminal punctuation position, or -1 when the OCR paragraph has no sentence boundary. */
    static int nextSentenceEnd(String value, int fromIndex) {
        int result = -1;
        for (char punctuation : new char[]{'。', '；', '！', '？'}) {
            int index = value.indexOf(punctuation, fromIndex);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }


    static String teacherResourceSourceTitle(TeacherResourceBlockSearchResponse.Hit hit) {
        StringBuilder builder = new StringBuilder(hit.documentTitle() == null || hit.documentTitle().isBlank()
                ? "教师资料"
                : hit.documentTitle().strip());
        if (hit.chapter() != null && !hit.chapter().isBlank()) {
            builder.append(" / ").append(hit.chapter().strip());
        }
        if (hit.section() != null && !hit.section().isBlank()
                && !hit.section().strip().equals(hit.chapter() == null ? "" : hit.chapter().strip())) {
            builder.append(" / ").append(hit.section().strip());
        }
        return builder.toString();
    }



    /** Applies the same mathematical-condition guard before a teacher block can become task evidence. */
    static boolean teacherHitRespectsColorCountConstraint(
            TeachingTaskRequest request,
            TeacherResourceBlockSearchResponse.Hit hit) {
        if (hit == null) {
            return false;
        }
        return sourceRespectsColorCountConstraint(
                request,
                teacherResourceSourceTitle(hit),
                hit.evidenceText(),
                hit.snippet());
    }


    /** Applies the condition guard to already materialized evidence used by fallback pack assembly. */
    static boolean evidenceRespectsColorCountConstraint(TeachingTaskRequest request, TeachingEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        return sourceRespectsColorCountConstraint(request, evidence.sourceTitle(), evidence.snippet());
    }


    /**
     * Prevents a neighbouring map-colouring variation (for example “6 种颜色”) from grounding a “4 种颜色” task.
     * A title is authoritative when it states a count; otherwise the synchronized source window must explicitly
     * contain the requested count. Sources without any count remain eligible because they may be a definition block.
     */
    static boolean sourceRespectsColorCountConstraint(TeachingTaskRequest request, String... sourceParts) {
        String question = request == null ? "" : safeQuestionText(request);
        if (!COLORING_TOPIC.matcher(question).find()) {
            return true;
        }
        Set<Integer> requestedCounts = colorCounts(question);
        if (requestedCounts.isEmpty()) {
            return true;
        }
        String title = sourceParts == null || sourceParts.length == 0 ? "" : normalizedInlineText(sourceParts[0]);
        Set<Integer> titleCounts = colorCounts(title);
        if (!titleCounts.isEmpty()) {
            return titleCounts.stream().anyMatch(requestedCounts::contains);
        }
        StringBuilder source = new StringBuilder();
        if (sourceParts != null) {
            for (String part : sourceParts) {
                if (part != null && !part.isBlank()) {
                    source.append(' ').append(part);
                }
            }
        }
        Set<Integer> sourceCounts = colorCounts(source.toString());
        return sourceCounts.isEmpty() || sourceCounts.stream().anyMatch(requestedCounts::contains);
    }


    /** Extracts explicit selectable-colour counts from Arabic or simple Chinese numerals. */
    static Set<Integer> colorCounts(String text) {
        Set<Integer> counts = new LinkedHashSet<>();
        Matcher matcher = COLOR_COUNT.matcher(normalizedInlineText(text));
        while (matcher.find()) {
            String token = matcher.group(1);
            Integer chineseValue = CHINESE_COLOR_COUNTS.get(token);
            if (chineseValue != null) {
                counts.add(chineseValue);
                continue;
            }
            try {
                counts.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // The pattern intentionally accepts a bounded vocabulary; an unfamiliar token simply does not form
                // a reliable condition and must not cause an otherwise authorized source to be rejected.
            }
        }
        return Set.copyOf(counts);
    }


    static List<TeachingEvidence> concatEvidence(List<TeachingEvidence>... groups) {
        List<TeachingEvidence> merged = new ArrayList<>();
        for (List<TeachingEvidence> group : groups) {
            if (group != null) {
                merged.addAll(group);
            }
        }
        return List.copyOf(merged);
    }


    /**
     * Collapses mirrored teacher-resource blocks before printable packs are assembled. The source token is used when
     * available because block ids legitimately differ after a document is re-synchronized; legacy records fall back
     * to a bounded normalized fingerprint. An authorized image always wins over its text-only mirror.
     */
    static List<TeachingEvidence> deduplicateSupportingEvidence(List<TeachingEvidence> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, TeachingEvidence> unique = new LinkedHashMap<>();
        for (TeachingEvidence candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String key = canonicalEvidenceKey(candidate);
            TeachingEvidence existing = unique.get(key);
            if (existing == null || shouldPreferEvidence(candidate, existing)) {
                unique.put(key, candidate);
            }
        }
        return List.copyOf(unique.values());
    }


    /** Builds a deterministic identity for either a stable teacher document or a normal immutable evidence block. */
    static String canonicalEvidenceKey(TeachingEvidence evidence) {
        String scope = normalizedInlineText(evidence.sourceScope());
        if (!"TEACHER_RESOURCE".equals(scope)) {
            return scope + ":" + normalizedInlineText(evidence.chunkId());
        }
        Matcher token = FEISHU_DOCUMENT_TOKEN.matcher(normalizedInlineText(evidence.sourceTitle()));
        if (token.find()) {
            /*
             * One synchronized Feishu document deliberately yields many atomic blocks (original question, each
             * diagram, later variations).  Deduplicate only a true mirror of the same block; collapsing by document
             * token alone discards the original map block and lets a neighbouring variation win by text length.
             */
            return scope + ":feishu:" + token.group(1).toLowerCase(Locale.ROOT)
                    + ":block:" + normalizedInlineText(evidence.chunkId());
        }
        String fingerprint = normalizedInlineText(evidence.sourceTitle() + " " + evidence.snippet())
                .replaceAll("\\s+", "");
        return scope + ":fingerprint:" + fingerprint.substring(0,
                Math.min(MAX_EVIDENCE_FINGERPRINT_CHARS, fingerprint.length()));
    }


    /** Prefer a renderable, permission-checked image; otherwise retain the longer useful source window. */
    static boolean shouldPreferEvidence(TeachingEvidence candidate, TeachingEvidence existing) {
        boolean candidateHasImage = candidate.imagePath() != null && !candidate.imagePath().isBlank();
        boolean existingHasImage = existing.imagePath() != null && !existing.imagePath().isBlank();
        if (candidateHasImage != existingHasImage) {
            return candidateHasImage;
        }
        return normalizedInlineText(candidate.snippet()).length() > normalizedInlineText(existing.snippet()).length();
    }


    static List<TeachingEvidence> alignEvidenceToTopic(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        // Benchmark/evaluation corpora are never teaching evidence. They are generated test fixtures and can
        // contain control prompts or deliberately vague prose that must not compete with the user's real sources.
        evidence = evidence.stream().filter(item -> !isBenchmarkEvidence(item)).toList();
        if (evidence.isEmpty()) {
            return List.of();
        }
        List<String> keywords = topicKeywords(request);
        if (keywords.isEmpty()) {
            return evidence;
        }
        /*
         * A broad word such as "函数" is useful for recall but is not a valid publication boundary.  The previous
         * score threshold treated one generic word as sufficient, so a quadratic lesson could publish derivative,
         * statistics, or trigonometry pages.  Apply the same concrete-topic guard used by the question bank before
         * score-based ranking; this keeps every evidence source on the requested curriculum branch.
         */
        List<String> specificTerms = specificEvidenceTopicTerms(request);
        if (!specificTerms.isEmpty()) {
            List<TeachingEvidence> specificallyAligned = evidence.stream()
                    .filter(item -> matchesSpecificEvidenceTopic(item, specificTerms))
                    .toList();
            if (!specificallyAligned.isEmpty()) {
                return specificallyAligned;
            }
            // A concrete topic with no verified matching page must remain an explicit evidence gap. Falling back to
            // the generic score here is what previously admitted parabola, statistics, and derivative pages.
            return List.of();
        }
        String primary = primaryTopicKeyword(request);
        int threshold = primary.length() >= 3
                ? primary.length()
                : Math.min(4, keywords.stream().mapToInt(String::length).max().orElse(2));
        // A directory section may intentionally contain sibling points. Requiring every hit to match the longest
        // first point silently drops the later sections, leaving them with no examples. In that case an evidence item
        // only has to match one concrete point; the subsequent packer keeps them separated.
        int perPointThreshold = keywords.size() > 1 ? 2 : threshold;
        List<TeachingEvidence> aligned = evidence.stream()
                .filter(item -> topicMatchScore(item, keywords) >= perPointThreshold)
                .toList();
        if (!aligned.isEmpty()) {
            return aligned;
        }
        if (hasLocalTeachingResource(evidence)) {
            List<String> expandedKeywords = localResourceTopicKeywords(request);
            if (!expandedKeywords.isEmpty()) {
                int expandedThreshold = Math.min(4,
                        expandedKeywords.stream().mapToInt(String::length).max().orElse(2));
                List<TeachingEvidence> expandedAligned = evidence.stream()
                        .filter(item -> topicMatchScore(item, expandedKeywords) >= expandedThreshold)
                        .toList();
                if (!expandedAligned.isEmpty()) {
                    return expandedAligned;
                }
            }
        }
        if (primary.isBlank()) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> compactEvidenceText(item).contains(primary.toLowerCase()))
                .toList();
    }


    static boolean isBenchmarkEvidence(TeachingEvidence evidence) {
        if (evidence == null) {
            return true;
        }
        String text = compactEvidenceText(evidence).toLowerCase(Locale.ROOT);

        return text.contains("synthetic-natural-math-benchmark")
                || text.contains("benchmark-high-school-math")
                || text.contains("/output/benchmarks/")
                || text.contains("\\output\\benchmarks\\")
                || text.contains("benchmark-math-resources")
                || text.contains("runtime-authored");
    }


    static boolean hasLocalTeachingResource(List<TeachingEvidence> evidence) {
        return evidence.stream().anyMatch(item -> !"PUBLIC_TEXTBOOK".equals(item.sourceScope()));
    }


    static List<String> localResourceTopicKeywords(TeachingTaskRequest request) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>(topicKeywords(request));
        for (String candidate : QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText())) {
            if (candidate.length() >= 2 && candidate.length() <= 12 && !TOPIC_GENERIC_TERMS.contains(candidate)) {
                keywords.add(candidate);
            }
        }
        return keywords.stream()
                .sorted(Comparator
                        .comparingInt((String keyword) -> CORE_TOPIC_PREFERENCES.contains(keyword) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .limit(12)
                .toList();
    }


    static boolean hasReadableHandoutContent(String value) {
        String normalized = safeFrontendText(value);
        if (normalized.length() < 18) {
            return false;

        }
        if (looksCorruptedText(normalized)) {
            return false;
        }
        if (containsProtocolOrDebugLeak(normalized)) {
            return false;
        }
        return true;
    }


    static boolean containsProtocolOrDebugLeak(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase().replaceAll("[\\s_\\-]+", "");
        return lower.contains("capability")
                || lower.contains("requesthash")
                || lower.contains("idempotencykey")
                || lower.contains("modelcall")
                || lower.contains("jsonparse")
                || lower.contains("apiaccess")
                || lower.contains("subjecttype")
                || lower.contains("bearer")
                || lower.contains("mcp")
                || lower.contains("安全探针")
                || lower.contains("不做题目生成")
                || lower.contains("模型健康")
                || lower.contains("调试信息")
                || lower.contains("内部提示词")
                || lower.contains("系统提示")
                || lower.contains("提示词")
                || lower.contains("{{");
    }


    static boolean looksCorruptedText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        if (normalized.contains("???") || normalized.contains("�")) {
            return true;
        }
        long questionMarks = normalized.chars().filter(ch -> ch == '?').count();
        if (questionMarks >= 3 && questionMarks * 2 >= normalized.length()) {
            return true;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("ã")
                || lower.contains("â")
                || lower.contains("ä¸")
                || lower.contains("å")
                || lower.contains("æ")
                || lower.contains("ç");
    }


    static String safeFrontendText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(value.strip());
            }
        }
        return builder.toString().replaceAll("\\s+", " ").strip();
    }


    static int topicMatchScore(TeachingEvidence evidence, List<String> keywords) {
        String haystack = compactEvidenceText(evidence);
        int score = 0;
        for (String keyword : keywords) {
            if (!keyword.isBlank() && haystack.contains(keyword.toLowerCase())) {
                score += keyword.length();
            }
        }
        return score;
    }


    static String compactEvidenceText(TeachingEvidence evidence) {
        return ((evidence.sourceTitle() == null ? "" : evidence.sourceTitle()) + " "
                + (evidence.snippet() == null ? "" : evidence.snippet()))
                .replaceAll("\\s+", "")
                .toLowerCase();
    }


    static List<String> topicKeywords(TeachingTaskRequest request) {
        String goalText = ((request.learningGoal() == null ? "" : request.learningGoal())
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")).toLowerCase();
        String questionText = ((request.questionText() == null ? "" : request.questionText())
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")).toLowerCase();
        String raw = ((request.learningGoal() == null ? "" : request.learningGoal()) + " "
                + (request.questionText() == null ? "" : request.questionText()))
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ");
        // Curriculum titles commonly join sibling points with 和/与/及. Split them before query construction so a
        // directory lesson such as “函数新概念与分段函数” retrieves both banks instead of treating it as one phrase.
        raw = raw.replaceAll("[和与及]", " ");
        raw = TOPIC_NOISE_WORD.matcher(raw).replaceAll(" ");
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        // Add the shared bank vocabulary before splitting whitespace. Chinese directory titles often concatenate
        // sibling points (for example “空间向量线面角”), so whitespace-only tokenization would lose the child point
        // and later alignment would discard its otherwise valid evidence.
        for (String candidate : QuestionBankSearchText.candidateQueries(request.learningGoal(), request.questionText())) {
            if (candidate.length() >= 2 && candidate.length() <= 18 && !TOPIC_GENERIC_TERMS.contains(candidate)) {
                keywords.add(candidate);
            }
        }
        for (String part : raw.split("\\s+")) {
            String candidate = part.strip();
            if (candidate.length() < 2) {
                continue;
            }
            if (TOPIC_GENERIC_TERMS.contains(candidate)) {
                continue;
            }
            keywords.add(candidate);
        }
        return keywords.stream()
                // Learning-goal vocabulary is the semantic contract.  Supplementary requirements are deliberately
                // lower priority because they often contain longer prose ("教师版原题答案...") that otherwise
                // displaces the actual topic before evidence alignment runs.
                .sorted(Comparator
                        .comparingInt((String keyword) -> topicKeywordPriority(keyword, goalText, questionText))
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .limit(8)
                .toList();
    }


    /** Returns request terms that identify a concrete topic instead of a broad mathematical domain. */
    static List<String> specificEvidenceTopicTerms(TeachingTaskRequest request) {
        String requestText = ((request == null || request.learningGoal() == null) ? "" : request.learningGoal()) + " "
                + ((request == null || request.questionText() == null) ? "" : request.questionText());
        List<String> candidates = QuestionBankSearchText.specificTopicTerms(requestText).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term))
                .filter(term -> !TOPIC_GENERIC_TERMS.contains(term))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (!candidates.isEmpty()) {
            return candidates;
        }
        return QuestionBankSearchText.candidateQueries(
                        request == null ? "" : request.learningGoal(),
                        request == null ? "" : request.questionText()).stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3 && term.length() <= 18)
                .filter(term -> !BROAD_TOPIC_TERMS.contains(term) && !TOPIC_GENERIC_TERMS.contains(term))
                .filter(term -> requestText.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                        .contains(term.toLowerCase(Locale.ROOT)))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }


    /** Matches concrete curriculum evidence, including common OCR variants of quadratic notation. */
    static boolean matchesSpecificEvidenceTopic(TeachingEvidence evidence, List<String> terms) {
        if (evidence == null || terms == null || terms.isEmpty()) {
            return false;
        }
        String text = compactEvidenceText(evidence).replaceAll("\\s+", "");
        // The longest request term is the primary topic. Secondary words such as “最小值” are constraints, not a
        // license to admit every generic minimum-value page from another chapter.
        for (String term : terms.stream().limit(1).toList()) {
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            if (text.contains(normalizedTerm)) {
                return true;
            }
            if ("二次函数".equals(term)
                    && text.contains("函数")
                    && (text.contains("x^2") || text.contains("x²") || text.contains("x2"))
                    && !text.contains("x^3") && !text.contains("x³") && !text.contains("x3")
                    && !text.contains("双曲线") && !text.contains("椭圆") && !text.contains("圆锥曲线")
                    && !text.contains("抛物线")) {
                return true;
            }

        }
        return false;
    }


    static int topicKeywordPriority(String keyword, String goalText, String questionText) {
        String normalized = keyword == null ? "" : keyword.toLowerCase();
        if (normalized.length() <= 8 && !normalized.isBlank() && goalText.contains(normalized)) {
            return 0;
        }
        if (normalized.length() <= 8 && !normalized.isBlank() && questionText.contains(normalized)) {
            return 1;
        }
        return 2;
    }


    static String primaryTopicKeyword(TeachingTaskRequest request) {
        List<String> keywords = topicKeywords(request);
        String goalText = ((request.learningGoal() == null ? "" : request.learningGoal())
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")).toLowerCase();
        // Prefer the concrete goal term over a broad domain term.  For example, 二次函数 must win over 函数 so a
        // generic statistics page containing the word 最值 cannot enter a quadratic-function handout.
        for (String keyword : keywords) {
            if (keyword.length() >= 3
                    && goalText.contains(keyword.toLowerCase())
                    && !BROAD_TOPIC_TERMS.contains(keyword)) {
                return keyword;
            }
        }
        for (String keyword : keywords) {
            if (CORE_TOPIC_PREFERENCES.contains(keyword)) {
                return keyword;
            }
        }
        return keywords.isEmpty() ? "" : keywords.getFirst();
    }
}
