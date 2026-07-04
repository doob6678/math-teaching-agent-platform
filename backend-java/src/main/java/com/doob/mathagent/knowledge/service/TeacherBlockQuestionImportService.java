package com.doob.mathagent.knowledge.service;

import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.knowledge.vo.TeacherBlockQuestionImportResponse;
import com.doob.mathagent.teacher.service.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Imports real parsed teacher resource blocks into the standard question bank.
 */
@Service
public class TeacherBlockQuestionImportService {

    private static final int TITLE_LIMIT = 80;

    private final TeacherResourceStore resourceStore;
    private final TeacherDocumentBlockStore blockStore;
    private final KnowledgeQuestionBankService questionBankService;
    private final KnowledgeQuestionBankStore questionBankStore;

    /**
     * Creates the import service.
     *
     * @param resourceStore teacher resource document store
     * @param blockStore parsed block store
     * @param questionBankService question bank write service
     * @param questionBankStore question bank de-duplication store
     */
    public TeacherBlockQuestionImportService(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            KnowledgeQuestionBankService questionBankService,
            KnowledgeQuestionBankStore questionBankStore) {
        this.resourceStore = resourceStore;
        this.blockStore = blockStore;
        this.questionBankService = questionBankService;
        this.questionBankStore = questionBankStore;
    }

    /**
     * Imports visible parsed blocks from one teacher resource document.
     */
    public TeacherBlockQuestionImportResponse importFromTeacherResource(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String documentId) {
        String normalizedTenantId = requireText(tenantId, "tenantId");
        String normalizedRole = requireText(viewerRole, "viewerRole").toLowerCase(Locale.ROOT);
        String normalizedSubjectId = requireText(viewerSubjectId, "viewerSubjectId");
        String normalizedDocumentId = requireText(documentId, "documentId");
        requireTeacherOrAdmin(normalizedRole);
        TeacherResourceDocumentResponse document = resourceStore.find(normalizedTenantId, normalizedDocumentId);
        if (document == null || !visibleForImport(document, normalizedRole, normalizedSubjectId)) {
            throw new IllegalArgumentException("Teacher resource document is not visible for import");
        }
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument(normalizedTenantId, normalizedDocumentId);
        List<QuestionBankItemResponse> imported = new ArrayList<>();
        Set<String> linkedKnowledgePointIds = new LinkedHashSet<>();
        int skipped = 0;
        int duplicates = 0;
        for (TeacherDocumentBlockResponse block : blocks) {
            String sourceChecksum = textOrDefault(block.checksum(), "");
            if (questionBankStore.findQuestionBySource(
                            normalizedTenantId,
                            document.documentId(),
                            block.blockId(),
                            sourceChecksum)
                    .isPresent()) {
                duplicates++;
                continue;
            }
            String questionText = textOrDefault(block.rawText(), block.normalizedText());
            if (!looksLikeQuestion(questionText)) {
                skipped++;
                continue;
            }
            KnowledgePointResponse point = questionBankService.ensureKnowledgePoint(
                    normalizedTenantId,
                    normalizedRole,
                    normalizedSubjectId,
                    document.permissionScope(),
                    knowledgePointName(block),
                    chapterPath(block),
                    "teacher_resource_import:" + document.documentId());
            QuestionBankItemResponse question = questionBankService.createImportedQuestion(
                    normalizedTenantId,
                    normalizedRole,
                    normalizedSubjectId,
                    document.permissionScope(),
                    title(questionText),
                    questionText,
                    "medium",
                    document.documentId(),
                    block.blockId(),
                    sourceChecksum,
                    List.of(point.knowledgePointId()));
            imported.add(question);
            linkedKnowledgePointIds.add(point.knowledgePointId());
        }
        return new TeacherBlockQuestionImportResponse(
                document.documentId(),
                blocks.size(),
                imported.size(),
                skipped,
                duplicates,
                linkedKnowledgePointIds.size(),
                List.copyOf(imported));
    }

    /**
     * Searches imported and manually created questions through the normal question-bank read path.
     */
    public List<QuestionBankItemResponse> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        return questionBankService.searchQuestions(tenantId, viewerRole, viewerSubjectId, query, limit);
    }

    /**
     * Uses backend role, owner and original source scope to decide import visibility.
     */
    private static boolean visibleForImport(
            TeacherResourceDocumentResponse document,
            String viewerRole,
            String viewerSubjectId) {
        if ("admin".equals(viewerRole)) {
            return true;
        }
        if (!"teacher".equals(viewerRole)) {
            return false;
        }
        return viewerSubjectId.equals(document.ownerSubjectId()) || isSharedScope(document.permissionScope());
    }

    /**
     * Allows import from shared teacher resources that are already exposed by the backend.
     */
    private static boolean isSharedScope(String permissionScope) {
        return "MATH_VIP".equals(permissionScope) || "PUBLIC_TEXTBOOK".equals(permissionScope);
    }

    /**
     * Conservatively identifies parsed blocks that contain a real math question prompt.
     */
    private static boolean looksLikeQuestion(String text) {
        String normalized = textOrDefault(text, "");
        return normalized.contains("求")
                || normalized.contains("证明")
                || normalized.contains("已知")
                || normalized.contains("例")
                || normalized.contains("题")
                || normalized.contains("?")
                || normalized.contains("？");
    }

    /**
     * Chooses section before chapter to avoid over-broad imported knowledge points.
     */
    private static String knowledgePointName(TeacherDocumentBlockResponse block) {
        String section = textOrDefault(block.section(), "");
        if (!section.isBlank()) {
            return section;
        }
        return textOrDefault(block.chapter(), "未归类知识点");
    }

    /**
     * Builds a stable chapter path from parsed document headings.
     */
    private static String chapterPath(TeacherDocumentBlockResponse block) {
        String chapter = textOrDefault(block.chapter(), "");
        String section = textOrDefault(block.section(), "");
        if (chapter.isBlank()) {
            return section;
        }
        if (section.isBlank()) {
            return chapter;
        }
        return chapter + "/" + section;
    }

    /**
     * Builds a compact question title from the real source text.
     */
    private static String title(String questionText) {
        String compact = textOrDefault(questionText, "").replaceAll("\\s+", " ");
        if (compact.length() <= TITLE_LIMIT) {
            return compact;
        }
        return compact.substring(0, TITLE_LIMIT);
    }

    /**
     * Ensures only teacher/admin backend subjects can import teacher resources.
     */
    private static void requireTeacherOrAdmin(String viewerRole) {
        if (!"teacher".equals(viewerRole) && !"admin".equals(viewerRole)) {
            throw new IllegalArgumentException("Teacher block question import requires teacher or admin role");
        }
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
     * Returns stripped text or a fallback when blank.
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
