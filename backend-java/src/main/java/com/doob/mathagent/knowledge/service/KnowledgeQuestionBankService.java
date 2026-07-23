package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.knowledge.dto.KnowledgePointCreateRequest;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.KnowledgeRelationResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorTextRerankResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for teacher/admin knowledge point and question bank management.
 */
@Service
public class KnowledgeQuestionBankService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionBankService.class);

    /** Maximum visible rows loaded for one UI query so client-side paging never paginates an arbitrary 50-row slice. */
    public static final int MAX_SEARCH_ROWS = 500;

    private final KnowledgeQuestionBankStore store;
    private final VectorIndexService vectorIndexService;

    /**
     * Creates a knowledge and question bank service.
     *
     * @param store store abstraction
     */
    public KnowledgeQuestionBankService(KnowledgeQuestionBankStore store) {
        // Disambiguate the two production/test constructors so the local test path still skips the optional worker.
        this(store, (VectorIndexService) null);
    }

    /**
     * Creates the production service with the same real BGE reranker used by textbook and teacher-resource search.
     * The one-argument constructor remains for isolated store tests where no HTTP worker is configured.
     */
    @Autowired
    public KnowledgeQuestionBankService(
            KnowledgeQuestionBankStore store,
            Optional<VectorIndexService> vectorIndexService) {
        this(store, vectorIndexService == null ? null : vectorIndexService.orElse(null));
    }

    /** Creates a testable service with an optional semantic reranker. */
    KnowledgeQuestionBankService(KnowledgeQuestionBankStore store, VectorIndexService vectorIndexService) {
        this.store = store;
        this.vectorIndexService = vectorIndexService;
    }

    /**
     * Creates a knowledge point using backend-resolved identity and effective scope rules.
     */
    public KnowledgePointResponse createKnowledgePoint(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            KnowledgePointCreateRequest request) {
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        KnowledgePointRecord record = new KnowledgePointRecord(
                UUID.randomUUID().toString(),
                requireText(tenantId, "tenantId"),
                requireText(viewerSubjectId, "viewerSubjectId"),
                normalizePermissionScope(request.permissionScope(), role),
                requireText(request.knowledgePointName(), "knowledgePointName"),
                textOrDefault(request.chapterPath(), ""),
                "active",
                textOrDefault(request.sourceSummary(), "manual"));
        return toResponse(store.saveKnowledgePoint(record));
    }

    /**
     * Creates a question bank item using backend-resolved identity and effective scope rules.
     */
    public QuestionBankItemResponse createQuestion(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            QuestionBankItemCreateRequest request) {
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        QuestionBankItemRecord record = new QuestionBankItemRecord(
                UUID.randomUUID().toString(),
                requireText(tenantId, "tenantId"),
                requireText(viewerSubjectId, "viewerSubjectId"),
                normalizePermissionScope(request.permissionScope(), role),
                requireText(request.questionTitle(), "questionTitle"),
                requireText(request.questionText(), "questionText"),
                textOrDefault(request.answerJson(), "{}"),
                textOrDefault(request.difficulty(), "medium"),
                "active",
                request.knowledgePointIds() == null ? List.of() : List.copyOf(request.knowledgePointIds()));
        return toResponse(store.saveQuestion(record));
    }

    /**
     * Creates or reuses a knowledge point with exact owner/scope/name/chapter identity.
     */
    public KnowledgePointResponse ensureKnowledgePoint(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String permissionScope,
            String knowledgePointName,
            String chapterPath,
            String sourceSummary) {
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        String normalizedTenantId = requireText(tenantId, "tenantId");
        String normalizedOwner = requireText(viewerSubjectId, "viewerSubjectId");
        String effectiveScope = normalizePermissionScope(permissionScope, role);
        String normalizedName = requireText(knowledgePointName, "knowledgePointName");
        String normalizedChapterPath = textOrDefault(chapterPath, "");
        Optional<KnowledgePointRecord> existing = store.findKnowledgePoint(
                normalizedTenantId,
                normalizedOwner,
                effectiveScope,
                normalizedName,
                normalizedChapterPath);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        KnowledgePointRecord record = new KnowledgePointRecord(
                UUID.randomUUID().toString(),
                normalizedTenantId,
                normalizedOwner,
                effectiveScope,
                normalizedName,
                normalizedChapterPath,
                "active",
                textOrDefault(sourceSummary, "teacher_resource_import"));
        return toResponse(store.saveKnowledgePoint(record));
    }

    /**
     * Creates an imported question with source metadata for sync resume and de-duplication.
     */
    public QuestionBankItemResponse createImportedQuestion(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String permissionScope,
            String questionTitle,
            String questionText,
            String difficulty,
            String sourceResourceDocumentId,
            String sourceBlockId,
            String sourceChecksum,
            List<String> knowledgePointIds) {
        return createImportedQuestion(
                tenantId,
                viewerRole,
                viewerSubjectId,
                permissionScope,
                questionTitle,
                questionText,
                "{}",
                difficulty,
                sourceResourceDocumentId,
                sourceBlockId,
                sourceChecksum,
                knowledgePointIds);
    }

    /**
     * Creates one imported source question while retaining a separately parsed answer.  Keeping the answer outside
     * {@code questionText} is essential: downstream handout renderers can then print the prompt once and put only
     * the source-verified answer in the teacher-only answer block.
     */
    public QuestionBankItemResponse createImportedQuestion(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String permissionScope,
            String questionTitle,
            String questionText,
            String answerJson,
            String difficulty,
            String sourceResourceDocumentId,
            String sourceBlockId,
            String sourceChecksum,
            List<String> knowledgePointIds) {
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        QuestionBankItemRecord record = new QuestionBankItemRecord(
                UUID.randomUUID().toString(),
                requireText(tenantId, "tenantId"),
                requireText(viewerSubjectId, "viewerSubjectId"),
                normalizePermissionScope(permissionScope, role),
                requireText(questionTitle, "questionTitle"),
                requireText(questionText, "questionText"),
                textOrDefault(answerJson, "{}"),
                textOrDefault(difficulty, "medium"),
                "active",
                textOrDefault(sourceResourceDocumentId, ""),
                textOrDefault(sourceBlockId, ""),
                textOrDefault(sourceChecksum, ""),
                knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds));
        return toResponse(store.saveQuestion(record));
    }

    /**
     * Lists visible knowledge points for the backend viewer.
     */
    public List<KnowledgePointResponse> listKnowledgePoints(
            String tenantId,
            String viewerRole,
            String viewerSubjectId) {
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        return store.listKnowledgePoints(
                        requireText(tenantId, "tenantId"),
                        role,
                        requireText(viewerSubjectId, "viewerSubjectId"))
                .stream()
                .map(KnowledgeQuestionBankService::toResponse)
                .toList();
    }

    /**
     * Lists visible knowledge graph relations for the backend viewer.
     */
    public List<KnowledgeRelationResponse> listKnowledgeRelations(
            String tenantId,
            String viewerRole,
            String viewerSubjectId) {
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        return store.listKnowledgeRelations(
                        requireText(tenantId, "tenantId"),
                        role,
                        requireText(viewerSubjectId, "viewerSubjectId"))
                .stream()
                .map(KnowledgeQuestionBankService::toResponse)
                .toList();
    }

    /**
     * Searches visible question bank items for the backend viewer.
     */
    public List<QuestionBankItemResponse> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
        String query,
            int limit) {
        String role = normalizeRole(viewerRole);
        // Search is a read-only operation.  The store applies tenant, public/shared, and owner-private visibility
        // rules for every role; keeping this method readable by students prevents the management-role guard from
        // turning the populated 2022-2024 bank into a misleading 403/zero-result screen.
        List<QuestionBankItemResponse> candidates = store.searchQuestions(
                        requireText(tenantId, "tenantId"),
                        role,
                        requireText(viewerSubjectId, "viewerSubjectId"),
                        normalizedQuestionQuery(query),
                        normalizedLimit(limit))
                .stream()
                .map(KnowledgeQuestionBankService::toResponse)
                .toList();
        List<QuestionBankItemResponse> topicAligned = strictTopicFilter(query, candidates);
        return rerank(query, topicAligned);
    }

    /**
     * Prevents broad SQL keyword expansion from leaking unrelated rows into a specific topic search.
     * An empty strict-term set means the caller asked for a broad browse and keeps the complete visible candidate set.
     */
    private static List<QuestionBankItemResponse> strictTopicFilter(
            String query,
            List<QuestionBankItemResponse> candidates) {
        List<String> strictTerms = QuestionBankSearchText.specificTopicTerms(query);
        if (strictTerms.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> containsAnyStrictTopic(candidate, strictTerms))
                .toList();
    }

    /** Allows OCR variants of quadratic notation while still rejecting rows that only contain the word "函数". */
    private static boolean containsAnyStrictTopic(
            QuestionBankItemResponse candidate,
            List<String> strictTerms) {
        String text = ((candidate.questionTitle() == null ? "" : candidate.questionTitle()) + " "
                + (candidate.questionText() == null ? "" : candidate.questionText()))
                .replaceAll("\\s+", "")
                .toLowerCase();
        int matchedStrictTerms = 0;
        for (String term : strictTerms) {
            if (text.contains(term.toLowerCase())) {
                matchedStrictTerms++;
                continue;
            }
            if ("二次函数".equals(term)
                    && text.contains("函数")
                    && (text.contains("x^2") || text.contains("x²") || text.contains("x2"))
                    // OCR frequently drops parentheses and the literal f(x). The combination of a function
                    // marker and a quadratic x-term is therefore the stable fallback; cubic/conic signatures
                    // are excluded so an x^2 term inside a higher-degree or conic question cannot leak in.
                    && !text.contains("x^3") && !text.contains("x³") && !text.contains("x3")
                    && !text.contains("双曲线") && !text.contains("椭圆") && !text.contains("圆锥曲线")) {
                matchedStrictTerms++;
            }
        }
        // A compound query such as "二次函数最值" must satisfy every concrete term; accepting an OR here lets a
        // generic quadratic transformation row displace the requested minimum-value examples.
        return matchedStrictTerms == strictTerms.size();
    }

    /**
     * Applies the production cross-encoder reranker to the visible candidate window. If the worker is unavailable,
     * VectorIndexService explicitly returns its embedding fallback and this method preserves the deterministic store
     * order instead of silently inventing a score.
     */
    private List<QuestionBankItemResponse> rerank(
            String query,
            List<QuestionBankItemResponse> candidates) {
        if (vectorIndexService == null || query == null || query.isBlank() || candidates.size() < 2) {
            return candidates;
        }
        List<String> texts = candidates.stream()
                .map(candidate -> ((candidate.questionTitle() == null ? "" : candidate.questionTitle()) + "\n"
                        + (candidate.questionText() == null ? "" : candidate.questionText())).strip())
                .toList();
        VectorTextRerankResult result;
        try {
            result = vectorIndexService.rerankTextsWithTrace(query, texts);
        } catch (RuntimeException exception) {
            // A broken worker must not turn a permission-checked lexical query into a blank page; retain the visible
            // candidate order and leave a server-side diagnostic for the retrieval audit instead.
            log.warn("question_bank_rerank_failed query={} candidates={} message={}", query, candidates.size(), exception.getMessage());
            return candidates;
        }
        if (result.scores().size() != candidates.size()) {
            return candidates;
        }
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingDouble((Integer index) -> result.scores().get(index)).reversed()
                .thenComparingInt(Integer::intValue));
        return order.stream().map(candidates::get).toList();
    }

    /**
     * Converts a record to API response.
     */
    private static KnowledgePointResponse toResponse(KnowledgePointRecord record) {
        return new KnowledgePointResponse(
                record.knowledgePointId(),
                record.tenantId(),
                record.ownerSubjectId(),
                record.permissionScope(),
                record.knowledgePointName(),
                record.chapterPath(),
                record.status(),
                record.sourceSummary());
    }

    /**
     * Converts a relation record to API response.
     */
    private static KnowledgeRelationResponse toResponse(KnowledgeRelationRecord record) {
        return new KnowledgeRelationResponse(
                record.relationId(),
                record.tenantId(),
                record.sourceKnowledgePointId(),
                record.targetKnowledgePointId(),
                record.relationType(),
                record.evidenceSummary(),
                record.status());
    }

    /**
     * Converts a question record to API response.
     */
    private static QuestionBankItemResponse toResponse(QuestionBankItemRecord record) {
        return new QuestionBankItemResponse(
                record.questionId(),
                record.tenantId(),
                record.ownerSubjectId(),
                record.permissionScope(),
                displayQuestionTitle(record),
                record.questionText(),
                record.answerJson(),
                record.difficulty(),
                record.status(),
                record.sourceResourceDocumentId(),
                record.sourceBlockId(),
                record.sourceChecksum(),
                record.knowledgePointIds());
    }

    /**
     * Keeps imported OCR separators out of user-facing title fields without mutating stored source data.
     */
    private static String displayQuestionTitle(QuestionBankItemRecord record) {
        String title = textOrDefault(record.questionTitle(), "");
        if (!isNoisyTitle(title)) {
            return title;
        }
        String fallback = displayTitleFromQuestionText(record.questionText());
        return fallback.isBlank() ? "题库题目（待清理）" : fallback;
    }

    private static boolean isNoisyTitle(String title) {
        if (title.isBlank()) {
            return true;
        }
        long separatorCount = title.chars()
                .filter(ch -> ch == '*' || ch == '-' || ch == '_' || ch == '=')
                .count();
        return separatorCount >= 12 || separatorCount * 2 > title.length();
    }

    private static String displayTitleFromQuestionText(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return "";
        }
        for (String line : questionText.split("\\R+")) {
            String cleaned = line
                    .replaceAll("[*_=-]{6,}", " ")
                    .replaceAll("\\s+", " ")
                    .strip();
            if (cleaned.equals("赵礼显数学") || cleaned.length() < 8 || isNoisyTitle(cleaned)) {
                continue;
            }
            return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
        }
        String compact = questionText
                .replaceAll("[*_=-]{6,}", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (compact.equals("赵礼显数学") || compact.length() < 8 || isNoisyTitle(compact)) {
            return "";
        }
        return compact.length() <= 80 ? compact : compact.substring(0, 80);
    }

    /**
     * Allows only teacher/admin writes and reads in this management surface.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Knowledge question bank management requires teacher or admin role");
        }
    }

    /**
     * Prevents non-admin users from self-granting shared/public scopes.
     */
    private static String normalizePermissionScope(String permissionScope, String viewerRole) {
        if (!"admin".equals(viewerRole)) {
            return "TEACHER_PRIVATE";
        }
        String normalized = textOrDefault(permissionScope, "TEACHER_PRIVATE").toUpperCase();
        if ("MATH_VIP".equals(normalized) || "PUBLIC_TEXTBOOK".equals(normalized)) {
            return normalized;
        }
        return "TEACHER_PRIVATE";
    }

    /**
     * Normalizes the backend viewer role.
     */
    private static String normalizeRole(String viewerRole) {
        return requireText(viewerRole, "viewerRole").toLowerCase();
    }

    /**
     * Normalizes question-bank search text while preserving an empty query as "browse latest".
     */
    private static String normalizedQuestionQuery(String query) {
        return textOrDefault(query, "").replaceAll("\\s+", " ").strip();
    }

    /**
     * Keeps question-bank browse/search bounded for frontend pagination.
     */
    private static int normalizedLimit(int limit) {
        return Math.max(1, Math.min(MAX_SEARCH_ROWS, limit));
    }

    /**
     * Requires non-blank text.
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.strip();
    }

    /**
     * Returns stripped text or default value.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
