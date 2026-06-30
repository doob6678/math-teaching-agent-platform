package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.knowledge.dto.KnowledgePointCreateRequest;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.KnowledgeRelationResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Service for teacher/admin knowledge point and question bank management.
 */
@Service
public class KnowledgeQuestionBankService {

    private final KnowledgeQuestionBankStore store;

    /**
     * Creates a knowledge and question bank service.
     *
     * @param store store abstraction
     */
    public KnowledgeQuestionBankService(KnowledgeQuestionBankStore store) {
        this.store = store;
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
                textOrDefault(tenantId, "default"),
                textOrDefault(viewerSubjectId, "local-teacher-console"),
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
                textOrDefault(tenantId, "default"),
                textOrDefault(viewerSubjectId, "local-teacher-console"),
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
        String normalizedTenantId = textOrDefault(tenantId, "default");
        String normalizedOwner = textOrDefault(viewerSubjectId, "local-teacher-console");
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
        String role = normalizeRole(viewerRole);
        requireTeacherOrAdmin(role);
        QuestionBankItemRecord record = new QuestionBankItemRecord(
                UUID.randomUUID().toString(),
                textOrDefault(tenantId, "default"),
                textOrDefault(viewerSubjectId, "local-teacher-console"),
                normalizePermissionScope(permissionScope, role),
                requireText(questionTitle, "questionTitle"),
                requireText(questionText, "questionText"),
                "{}",
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
                        textOrDefault(tenantId, "default"),
                        role,
                        textOrDefault(viewerSubjectId, "local-teacher-console"))
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
                        textOrDefault(tenantId, "default"),
                        role,
                        textOrDefault(viewerSubjectId, "local-teacher-console"))
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
        requireTeacherOrAdmin(role);
        return store.searchQuestions(
                        textOrDefault(tenantId, "default"),
                        role,
                        textOrDefault(viewerSubjectId, "local-teacher-console"),
                        textOrDefault(query, ""),
                        limit)
                .stream()
                .map(KnowledgeQuestionBankService::toResponse)
                .toList();
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
                record.questionTitle(),
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
        return textOrDefault(viewerRole, "teacher").toLowerCase();
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
