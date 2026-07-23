package com.doob.mathagent.student.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.entity.StudentExplanationMessageEntity;
import com.doob.mathagent.student.entity.StudentExplanationSessionEntity;
import com.doob.mathagent.student.mapper.StudentExplanationMessageMapper;
import com.doob.mathagent.student.mapper.StudentExplanationSessionMapper;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis-backed durable explanation history store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisStudentExplanationHistoryStore implements StudentExplanationHistoryStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final StudentExplanationSessionMapper sessionMapper;
    private final StudentExplanationMessageMapper messageMapper;

    public MyBatisStudentExplanationHistoryStore(
            StudentExplanationSessionMapper sessionMapper,
            StudentExplanationMessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    @Transactional
    public void save(
            StudentExplanationRequest request,
            RequestSubject subject,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationResponse response) {
        StudentExplanationSessionEntity session = sessionMapper.selectById(response.conversationId());
        if (session == null) {
            session = new StudentExplanationSessionEntity();
            session.setConversationId(response.conversationId());
            session.setTenantId(subject.tenantId());
            session.setSubjectType(subject.subjectType());
            session.setSubjectId(subject.subjectId());
            session.setStudentId(response.studentId());
            session.setViewerRole(response.viewerRole());
            session.setTitle(response.conversationTitle());
            session.setTotalMessages(0);
            sessionMapper.insert(session);
        }
        session.setTitle(StudentExplanationConversationTitleSupport.resolvePersisted(
                response.conversationTitle(),
                response.questionText(),
                session.getCreatedAt()));
        session.setLastExplanationId(response.explanationId());
        session.setLastQuestionText(response.questionText());
        session.setTotalMessages((session.getTotalMessages() == null ? 0 : session.getTotalMessages()) + 1);
        sessionMapper.updateById(session);

        StudentExplanationMessageEntity message = new StudentExplanationMessageEntity();
        message.setExplanationId(response.explanationId());
        message.setConversationId(response.conversationId());
        message.setTenantId(subject.tenantId());
        message.setSubjectType(subject.subjectType());
        message.setSubjectId(subject.subjectId());
        message.setStudentId(response.studentId());
        message.setViewerRole(response.viewerRole());
        message.setQuestionText(response.questionText());
        message.setImageUploadId(imageRecord == null ? request.imageUploadId() : imageRecord.uploadId());
        message.setImageStatus(response.imageStatus());
        message.setImageProblemText(response.imageUnderstanding().problemText());
        message.setAiProviderName(response.aiDraft().providerName());
        message.setAiModelCode(response.aiDraft().modelCode());
        message.setPromptTokens(response.aiDraft().promptTokens());
        message.setCompletionTokens(response.aiDraft().completionTokens());
        message.setTotalTokens(response.aiDraft().totalTokens() + response.imageUnderstanding().totalTokens());
        message.setTotalElapsedMs(response.totalElapsedMs());
        message.setRequestJson(toJson(request));
        message.setImageUnderstandingJson(toJson(response.imageUnderstanding()));
        message.setAiDraftJson(toJson(response.aiDraft()));
        message.setWorkflowStagesJson(toJson(response.workflowStages()));
        message.setCardsJson(toJson(response.cards()));
        message.setSourcesJson(toJson(response.sources()));
        messageMapper.insert(message);
    }

    @Override
    public List<StudentExplanationHistorySummary> findRecent(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        LambdaQueryWrapper<StudentExplanationMessageEntity> wrapper =
                new LambdaQueryWrapper<StudentExplanationMessageEntity>()
                        .eq(StudentExplanationMessageEntity::getTenantId, tenantId)
                        .eq(StudentExplanationMessageEntity::getSubjectType, subjectType)
                        .eq(StudentExplanationMessageEntity::getSubjectId, subjectId)
                        .orderByDesc(StudentExplanationMessageEntity::getCreatedAt)
                        .orderByDesc(StudentExplanationMessageEntity::getExplanationId);
        if (conversationId != null && !conversationId.isBlank()) {
            wrapper.eq(StudentExplanationMessageEntity::getConversationId, conversationId.strip());
        }
        Page<StudentExplanationMessageEntity> page = messageMapper.selectPage(Page.of(1, boundedLimit), wrapper);
        Map<String, StudentExplanationSessionEntity> sessionsByConversationId = loadSessions(page.getRecords().stream()
                .map(StudentExplanationMessageEntity::getConversationId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
        return page.getRecords().stream()
                .map(entity -> toSummary(entity, sessionsByConversationId.get(entity.getConversationId())))
                .toList();
    }

    @Override
    public List<StudentExplanationConversationSummary> listConversations(
            String tenantId,
            String subjectType,
            String subjectId,
            int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 30));
        LambdaQueryWrapper<StudentExplanationSessionEntity> wrapper =
                new LambdaQueryWrapper<StudentExplanationSessionEntity>()
                        .eq(StudentExplanationSessionEntity::getTenantId, tenantId)
                        .eq(StudentExplanationSessionEntity::getSubjectType, subjectType)
                        .eq(StudentExplanationSessionEntity::getSubjectId, subjectId)
                        .orderByDesc(StudentExplanationSessionEntity::getUpdatedAt)
                        .orderByDesc(StudentExplanationSessionEntity::getCreatedAt);
        Page<StudentExplanationSessionEntity> page = sessionMapper.selectPage(Page.of(1, boundedLimit), wrapper);
        return page.getRecords().stream().map(MyBatisStudentExplanationHistoryStore::toConversationSummary).toList();
    }

    @Override
    public StudentExplanationConversationDetail loadConversation(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        StudentExplanationSessionEntity session = sessionMapper.selectById(conversationId.strip());
        if (session == null
                || !tenantId.equals(session.getTenantId())
                || !subjectType.equals(session.getSubjectType())
                || !subjectId.equals(session.getSubjectId())) {
            return null;
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        LambdaQueryWrapper<StudentExplanationMessageEntity> wrapper =
                new LambdaQueryWrapper<StudentExplanationMessageEntity>()
                        .eq(StudentExplanationMessageEntity::getTenantId, tenantId)
                        .eq(StudentExplanationMessageEntity::getSubjectType, subjectType)
                        .eq(StudentExplanationMessageEntity::getSubjectId, subjectId)
                        .eq(StudentExplanationMessageEntity::getConversationId, conversationId.strip())
                        .orderByAsc(StudentExplanationMessageEntity::getCreatedAt)
                        .orderByAsc(StudentExplanationMessageEntity::getExplanationId);
        Page<StudentExplanationMessageEntity> page = messageMapper.selectPage(Page.of(1, boundedLimit), wrapper);
        return new StudentExplanationConversationDetail(
                session.getConversationId(),
                session.getTenantId(),
                session.getSubjectType(),
                session.getSubjectId(),
                session.getStudentId(),
                session.getViewerRole(),
                StudentExplanationConversationTitleSupport.resolvePersisted(
                        session.getTitle(),
                        session.getLastQuestionText(),
                        session.getCreatedAt()),
                session.getTotalMessages() == null ? 0 : session.getTotalMessages(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                page.getRecords().stream().map(MyBatisStudentExplanationHistoryStore::toConversationMessage).toList());
    }

    private static StudentExplanationHistorySummary toSummary(
            StudentExplanationMessageEntity entity,
            StudentExplanationSessionEntity session) {
        LocalDateTime createdAt = entity.getCreatedAt();
        return new StudentExplanationHistorySummary(
                entity.getExplanationId(),
                entity.getConversationId(),
                StudentExplanationConversationTitleSupport.resolvePersisted(
                        session == null ? "" : session.getTitle(),
                        session == null ? entity.getQuestionText() : session.getLastQuestionText(),
                        session == null ? createdAt : session.getCreatedAt()),
                entity.getTenantId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getStudentId(),
                entity.getViewerRole(),
                entity.getQuestionText(),
                entity.getImageStatus(),
                entity.getImageProblemText(),
                entity.getAiProviderName(),
                entity.getAiModelCode(),
                entity.getTotalTokens() == null ? 0 : entity.getTotalTokens(),
                entity.getTotalElapsedMs() == null ? 0L : entity.getTotalElapsedMs(),
                createdAt);
    }

    private static StudentExplanationConversationSummary toConversationSummary(StudentExplanationSessionEntity entity) {
        return new StudentExplanationConversationSummary(
                entity.getConversationId(),
                entity.getTenantId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getStudentId(),
                entity.getViewerRole(),
                StudentExplanationConversationTitleSupport.resolvePersisted(
                        entity.getTitle(),
                        entity.getLastQuestionText(),
                        entity.getCreatedAt()),
                text(entity.getLastQuestionText()),
                entity.getTotalMessages() == null ? 0 : entity.getTotalMessages(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static StudentExplanationConversationDetail.Message toConversationMessage(StudentExplanationMessageEntity entity) {
        StudentExplanationRequest request = readJson(entity.getRequestJson(), StudentExplanationRequest.class);
        StudentExplanationResponse.ImageUnderstanding imageUnderstanding = readJson(
                entity.getImageUnderstandingJson(),
                StudentExplanationResponse.ImageUnderstanding.class);
        StudentExplanationResponse.AiDraft aiDraft = readJson(
                entity.getAiDraftJson(),
                StudentExplanationResponse.AiDraft.class);
        StudentExplanationResponse response = aiDraft == null
                ? fallbackResponse(entity)
                : new StudentExplanationResponse(
                        entity.getExplanationId(),
                        entity.getConversationId(),
                        StudentExplanationConversationTitleSupport.resolvePersisted("", entity.getQuestionText(), entity.getCreatedAt()),
                        text(entity.getTenantId()),
                        text(entity.getStudentId()),
                        text(entity.getViewerRole()),
                        text(entity.getQuestionText()),
                        text(entity.getImageStatus()),
                        imageUnderstanding == null ? StudentExplanationResponse.ImageUnderstanding.none() : imageUnderstanding,
                        "student_explanation_card_orchestrator_v0.2",
                        aiDraft,
                        readJsonList(entity.getWorkflowStagesJson(), StudentExplanationResponse.WorkflowStage[].class),
                        readJsonList(entity.getCardsJson(), StudentExplanationResponse.ExplanationCard[].class),
                        readJsonList(entity.getSourcesJson(), StudentExplanationResponse.ExplanationSource[].class),
                        entity.getTotalElapsedMs() == null ? 0L : entity.getTotalElapsedMs());
        return new StudentExplanationConversationDetail.Message(
                entity.getExplanationId(),
                text(entity.getQuestionText()),
                text(entity.getImageStatus()),
                text(entity.getImageProblemText()),
                request == null ? "" : text(request.imageFileName()),
                entity.getCreatedAt(),
                response);
    }

    private static StudentExplanationResponse fallbackResponse(StudentExplanationMessageEntity entity) {
        return new StudentExplanationResponse(
                entity.getExplanationId(),
                entity.getConversationId(),
                StudentExplanationConversationTitleSupport.resolvePersisted("", entity.getQuestionText(), entity.getCreatedAt()),
                text(entity.getTenantId()),
                text(entity.getStudentId()),
                text(entity.getViewerRole()),
                text(entity.getQuestionText()),
                text(entity.getImageStatus()),
                readJson(entity.getImageUnderstandingJson(), StudentExplanationResponse.ImageUnderstanding.class) == null
                        ? StudentExplanationResponse.ImageUnderstanding.none()
                        : readJson(entity.getImageUnderstandingJson(), StudentExplanationResponse.ImageUnderstanding.class),
                "student_explanation_card_orchestrator_v0.2",
                StudentExplanationResponse.AiDraft.disabled("history_recovered"),
                List.of(),
                List.of(),
                List.of(),
                entity.getTotalElapsedMs() == null ? 0L : entity.getTotalElapsedMs());
    }

    private static <T> T readJson(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read explanation history JSON", e);
        }
    }

    private static <T> List<T> readJsonList(String value, Class<T[]> arrayType) {
        T[] items = readJson(value, arrayType);
        return items == null ? List.of() : List.copyOf(Arrays.asList(items));
    }

    /**
     * 批量查会话标题，避免历史消息列表按条回表。
     */
    private Map<String, StudentExplanationSessionEntity> loadSessions(Set<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        return sessionMapper.selectBatchIds(conversationIds).stream()
                .collect(Collectors.toMap(
                        StudentExplanationSessionEntity::getConversationId,
                        Function.identity(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize explanation history JSON", e);
        }
    }
}
