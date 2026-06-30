package com.doob.mathagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.knowledge.entity.KnowledgePointEntity;
import com.doob.mathagent.knowledge.entity.QuestionBankItemEntity;
import com.doob.mathagent.knowledge.entity.QuestionKnowledgeLinkEntity;
import com.doob.mathagent.knowledge.mapper.KnowledgePointMapper;
import com.doob.mathagent.knowledge.mapper.QuestionBankItemMapper;
import com.doob.mathagent.knowledge.mapper.QuestionKnowledgeLinkMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed store for knowledge points and question bank items.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisKnowledgeQuestionBankStore implements KnowledgeQuestionBankStore {

    private final KnowledgePointMapper knowledgePointMapper;
    private final QuestionBankItemMapper questionMapper;
    private final QuestionKnowledgeLinkMapper linkMapper;

    /**
     * Creates a MyBatis store.
     */
    public MyBatisKnowledgeQuestionBankStore(
            KnowledgePointMapper knowledgePointMapper,
            QuestionBankItemMapper questionMapper,
            QuestionKnowledgeLinkMapper linkMapper) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.questionMapper = questionMapper;
        this.linkMapper = linkMapper;
    }

    /**
     * Inserts one knowledge point row.
     */
    @Override
    public KnowledgePointRecord saveKnowledgePoint(KnowledgePointRecord record) {
        knowledgePointMapper.insert(toEntity(record));
        return record;
    }

    /**
     * Inserts one question row and active manual links.
     */
    @Override
    public QuestionBankItemRecord saveQuestion(QuestionBankItemRecord record) {
        questionMapper.insert(toEntity(record));
        for (String knowledgePointId : record.knowledgePointIds()) {
            QuestionKnowledgeLinkEntity link = new QuestionKnowledgeLinkEntity();
            link.setLinkId(UUID.randomUUID().toString());
            link.setTenantId(record.tenantId());
            link.setQuestionId(record.questionId());
            link.setKnowledgePointId(knowledgePointId);
            link.setConfidence(1.0);
            link.setBindType("manual");
            link.setStatus("active");
            linkMapper.insert(link);
        }
        return record;
    }

    /**
     * Lists visible active knowledge points.
     */
    @Override
    public List<KnowledgePointRecord> listKnowledgePoints(String tenantId, String viewerRole, String viewerSubjectId) {
        LambdaQueryWrapper<KnowledgePointEntity> query = new LambdaQueryWrapper<KnowledgePointEntity>()
                .eq(KnowledgePointEntity::getTenantId, tenantId)
                .eq(KnowledgePointEntity::getStatus, "active")
                .orderByAsc(KnowledgePointEntity::getKnowledgePointName);
        applyVisibility(query, viewerRole, viewerSubjectId);
        return knowledgePointMapper.selectList(query).stream().map(MyBatisKnowledgeQuestionBankStore::toRecord).toList();
    }

    /**
     * Searches visible active questions.
     */
    @Override
    public List<QuestionBankItemRecord> searchQuestions(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String query,
            int limit) {
        LambdaQueryWrapper<QuestionBankItemEntity> wrapper = new LambdaQueryWrapper<QuestionBankItemEntity>()
                .eq(QuestionBankItemEntity::getTenantId, tenantId)
                .eq(QuestionBankItemEntity::getStatus, "active")
                .orderByAsc(QuestionBankItemEntity::getQuestionTitle);
        applyQuestionVisibility(wrapper, viewerRole, viewerSubjectId);
        if (query != null && !query.isBlank()) {
            String keyword = query.strip();
            wrapper.and(nested -> nested
                    .like(QuestionBankItemEntity::getQuestionTitle, keyword)
                    .or()
                    .like(QuestionBankItemEntity::getQuestionText, keyword));
        }
        return questionMapper.selectList(wrapper).stream()
                .limit(Math.max(1, Math.min(50, limit)))
                .map(entity -> toRecord(entity, links(tenantId, entity.getQuestionId())))
                .toList();
    }

    /**
     * Applies knowledge point visibility filters.
     */
    private static void applyVisibility(
            LambdaQueryWrapper<KnowledgePointEntity> query,
            String viewerRole,
            String viewerSubjectId) {
        if ("admin".equals(viewerRole)) {
            return;
        }
        query.and(nested -> nested
                .in(KnowledgePointEntity::getPermissionScope, List.of("MATH_VIP", "PUBLIC_TEXTBOOK"))
                .or(scope -> scope
                        .eq(KnowledgePointEntity::getPermissionScope, "TEACHER_PRIVATE")
                        .eq(KnowledgePointEntity::getOwnerSubjectId, viewerSubjectId)));
    }

    /**
     * Applies question visibility filters.
     */
    private static void applyQuestionVisibility(
            LambdaQueryWrapper<QuestionBankItemEntity> query,
            String viewerRole,
            String viewerSubjectId) {
        if ("admin".equals(viewerRole)) {
            return;
        }
        query.and(nested -> nested
                .in(QuestionBankItemEntity::getPermissionScope, List.of("MATH_VIP", "PUBLIC_TEXTBOOK"))
                .or(scope -> scope
                        .eq(QuestionBankItemEntity::getPermissionScope, "TEACHER_PRIVATE")
                        .eq(QuestionBankItemEntity::getOwnerSubjectId, viewerSubjectId)));
    }

    /**
     * Loads active knowledge point links for one question.
     */
    private List<String> links(String tenantId, String questionId) {
        return linkMapper.selectList(new LambdaQueryWrapper<QuestionKnowledgeLinkEntity>()
                        .eq(QuestionKnowledgeLinkEntity::getTenantId, tenantId)
                        .eq(QuestionKnowledgeLinkEntity::getQuestionId, questionId)
                        .eq(QuestionKnowledgeLinkEntity::getStatus, "active"))
                .stream()
                .map(QuestionKnowledgeLinkEntity::getKnowledgePointId)
                .toList();
    }

    /**
     * Converts a record to entity.
     */
    private static KnowledgePointEntity toEntity(KnowledgePointRecord record) {
        KnowledgePointEntity entity = new KnowledgePointEntity();
        entity.setKnowledgePointId(record.knowledgePointId());
        entity.setTenantId(record.tenantId());
        entity.setOwnerSubjectId(record.ownerSubjectId());
        entity.setPermissionScope(record.permissionScope());
        entity.setKnowledgePointName(record.knowledgePointName());
        entity.setChapterPath(record.chapterPath());
        entity.setStatus(record.status());
        entity.setSourceSummary(record.sourceSummary());
        return entity;
    }

    /**
     * Converts a record to entity.
     */
    private static QuestionBankItemEntity toEntity(QuestionBankItemRecord record) {
        QuestionBankItemEntity entity = new QuestionBankItemEntity();
        entity.setQuestionId(record.questionId());
        entity.setTenantId(record.tenantId());
        entity.setOwnerSubjectId(record.ownerSubjectId());
        entity.setPermissionScope(record.permissionScope());
        entity.setQuestionTitle(record.questionTitle());
        entity.setQuestionText(record.questionText());
        entity.setAnswerJson(record.answerJson());
        entity.setDifficulty(record.difficulty());
        entity.setStatus(record.status());
        return entity;
    }

    /**
     * Converts entity to service record.
     */
    private static KnowledgePointRecord toRecord(KnowledgePointEntity entity) {
        return new KnowledgePointRecord(
                entity.getKnowledgePointId(),
                entity.getTenantId(),
                entity.getOwnerSubjectId(),
                entity.getPermissionScope(),
                entity.getKnowledgePointName(),
                entity.getChapterPath(),
                entity.getStatus(),
                entity.getSourceSummary());
    }

    /**
     * Converts entity to service record.
     */
    private static QuestionBankItemRecord toRecord(QuestionBankItemEntity entity, List<String> knowledgePointIds) {
        return new QuestionBankItemRecord(
                entity.getQuestionId(),
                entity.getTenantId(),
                entity.getOwnerSubjectId(),
                entity.getPermissionScope(),
                entity.getQuestionTitle(),
                entity.getQuestionText(),
                entity.getAnswerJson(),
                entity.getDifficulty(),
                entity.getStatus(),
                knowledgePointIds);
    }
}
