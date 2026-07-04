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
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis-backed durable explanation history store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisStudentExplanationHistoryStore implements StudentExplanationHistoryStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            session.setTotalMessages(0);
            sessionMapper.insert(session);
        }
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
        return page.getRecords().stream().map(MyBatisStudentExplanationHistoryStore::toSummary).toList();
    }

    private static StudentExplanationHistorySummary toSummary(StudentExplanationMessageEntity entity) {
        LocalDateTime createdAt = entity.getCreatedAt();
        return new StudentExplanationHistorySummary(
                entity.getExplanationId(),
                entity.getConversationId(),
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

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize explanation history JSON", e);
        }
    }
}
