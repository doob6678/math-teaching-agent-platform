package com.doob.mathagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.knowledge.entity.KnowledgePointEntity;
import com.doob.mathagent.knowledge.entity.KnowledgeRelationEntity;
import com.doob.mathagent.knowledge.entity.QuestionBankItemEntity;
import com.doob.mathagent.knowledge.entity.QuestionKnowledgeLinkEntity;
import com.doob.mathagent.knowledge.mapper.KnowledgePointMapper;
import com.doob.mathagent.knowledge.mapper.KnowledgeRelationMapper;
import com.doob.mathagent.knowledge.mapper.QuestionBankItemMapper;
import com.doob.mathagent.knowledge.mapper.QuestionKnowledgeLinkMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final KnowledgeRelationMapper relationMapper;
    private final QuestionBankItemMapper questionMapper;
    private final QuestionKnowledgeLinkMapper linkMapper;

    /**
     * Creates a MyBatis store.
     */
    public MyBatisKnowledgeQuestionBankStore(
            KnowledgePointMapper knowledgePointMapper,
            KnowledgeRelationMapper relationMapper,
            QuestionBankItemMapper questionMapper,
            QuestionKnowledgeLinkMapper linkMapper) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.relationMapper = relationMapper;
        this.questionMapper = questionMapper;
        this.linkMapper = linkMapper;
    }

    /**
     * Inserts or updates one knowledge point row by deterministic id.
     */
    @Override
    public KnowledgePointRecord saveKnowledgePoint(KnowledgePointRecord record) {
        KnowledgePointEntity entity = toEntity(record);
        if (knowledgePointMapper.selectById(record.knowledgePointId()) == null) {
            knowledgePointMapper.insert(entity);
        } else {
            knowledgePointMapper.updateById(entity);
        }
        return record;
    }

    /**
     * Inserts or updates one knowledge relation row by deterministic id.
     */
    @Override
    public KnowledgeRelationRecord saveKnowledgeRelation(KnowledgeRelationRecord record) {
        KnowledgeRelationEntity entity = toEntity(record);
        if (relationMapper.selectById(record.relationId()) == null) {
            relationMapper.insert(entity);
        } else {
            relationMapper.updateById(entity);
        }
        return record;
    }

    /**
     * Finds an active knowledge point by deterministic import identity.
     */
    @Override
    public Optional<KnowledgePointRecord> findKnowledgePoint(
            String tenantId,
            String ownerSubjectId,
            String permissionScope,
            String knowledgePointName,
            String chapterPath) {
        LambdaQueryWrapper<KnowledgePointEntity> query = new LambdaQueryWrapper<KnowledgePointEntity>()
                .eq(KnowledgePointEntity::getTenantId, tenantId)
                .eq(KnowledgePointEntity::getOwnerSubjectId, ownerSubjectId)
                .eq(KnowledgePointEntity::getPermissionScope, permissionScope)
                .eq(KnowledgePointEntity::getKnowledgePointName, knowledgePointName)
                .eq(KnowledgePointEntity::getChapterPath, chapterPath)
                .eq(KnowledgePointEntity::getStatus, "active");
        return knowledgePointMapper.selectList(query).stream()
                .findFirst()
                .map(MyBatisKnowledgeQuestionBankStore::toRecord);
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
     * Finds an active imported question by teacher-resource source metadata.
     */
    @Override
    public Optional<QuestionBankItemRecord> findQuestionBySource(
            String tenantId,
            String sourceResourceDocumentId,
            String sourceBlockId,
            String sourceChecksum) {
        LambdaQueryWrapper<QuestionBankItemEntity> query = new LambdaQueryWrapper<QuestionBankItemEntity>()
                .eq(QuestionBankItemEntity::getTenantId, tenantId)
                .eq(QuestionBankItemEntity::getSourceResourceDocumentId, sourceResourceDocumentId)
                .eq(QuestionBankItemEntity::getSourceBlockId, sourceBlockId)
                .eq(QuestionBankItemEntity::getSourceChecksum, sourceChecksum)
                .eq(QuestionBankItemEntity::getStatus, "active");
        return questionMapper.selectList(query).stream()
                .findFirst()
                .map(entity -> toRecord(entity, links(tenantId, entity.getQuestionId())));
    }

    @Override
    public int archiveQuestionsBySourceDocumentExcept(
            String tenantId,
            String sourceResourceDocumentId,
            Set<String> activeSourceKeys) {
        List<QuestionBankItemEntity> existing = questionMapper.selectList(new LambdaQueryWrapper<QuestionBankItemEntity>()
                .eq(QuestionBankItemEntity::getTenantId, tenantId)
                .eq(QuestionBankItemEntity::getSourceResourceDocumentId, sourceResourceDocumentId)
                .eq(QuestionBankItemEntity::getStatus, "active"));
        int archived = 0;
        for (QuestionBankItemEntity entity : existing) {
            String sourceKey = sourceKey(entity.getSourceBlockId(), entity.getSourceChecksum());
            if (activeSourceKeys.contains(sourceKey)) {
                continue;
            }
            /*
             * Archive stale imported questions instead of deleting them. This keeps downstream auditability while
             * preventing old block/checksum variants from surviving a source sync and polluting retrieval or review.
             */
            questionMapper.update(
                    null,
                    new LambdaUpdateWrapper<QuestionBankItemEntity>()
                            .eq(QuestionBankItemEntity::getQuestionId, entity.getQuestionId())
                            .set(QuestionBankItemEntity::getStatus, "archived"));
            archived += 1;
        }
        return archived;
    }

    /** Archives one stale parser representation while preserving the database row for audit review. */
    @Override
    public boolean archiveQuestion(String tenantId, String questionId) {
        return questionMapper.update(
                null,
                new LambdaUpdateWrapper<QuestionBankItemEntity>()
                        .eq(QuestionBankItemEntity::getTenantId, tenantId)
                        .eq(QuestionBankItemEntity::getQuestionId, questionId)
                        .eq(QuestionBankItemEntity::getStatus, "active")
                        .set(QuestionBankItemEntity::getStatus, "archived")) > 0;
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
     * Lists active relations whose source and target points are both visible to the viewer.
     */
    @Override
    public List<KnowledgeRelationRecord> listKnowledgeRelations(String tenantId, String viewerRole, String viewerSubjectId) {
        Set<String> visiblePointIds = listKnowledgePoints(tenantId, viewerRole, viewerSubjectId).stream()
                .map(KnowledgePointRecord::knowledgePointId)
                .collect(java.util.stream.Collectors.toSet());
        if (visiblePointIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgeRelationEntity> query = new LambdaQueryWrapper<KnowledgeRelationEntity>()
                .eq(KnowledgeRelationEntity::getTenantId, tenantId)
                .eq(KnowledgeRelationEntity::getStatus, "active")
                .in(KnowledgeRelationEntity::getSourceKnowledgePointId, visiblePointIds)
                .in(KnowledgeRelationEntity::getTargetKnowledgePointId, visiblePointIds)
                .orderByAsc(KnowledgeRelationEntity::getRelationId);
        return relationMapper.selectList(query).stream()
                .map(MyBatisKnowledgeQuestionBankStore::toRecord)
                .toList();
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
                .eq(QuestionBankItemEntity::getStatus, "active");
        applyQuestionVisibility(wrapper, viewerRole, viewerSubjectId);
        if (query != null && !query.isBlank()) {
            List<String> keywords = QuestionBankSearchText.keywords(query);
            wrapper.and(nested -> {
                nested.like(QuestionBankItemEntity::getQuestionTitle, query.strip())
                        .or()
                        .like(QuestionBankItemEntity::getQuestionText, query.strip());
                if (!keywords.isEmpty()) {
                    nested.or(keywordGroup -> {
                        for (String keyword : keywords) {
                            keywordGroup
                                    .or()
                                    .like(QuestionBankItemEntity::getQuestionTitle, keyword)
                                    .or()
                                    .like(QuestionBankItemEntity::getQuestionText, keyword)
                                    .or()
                                    .like(QuestionBankItemEntity::getSourceBlockId, keyword);
                        }
                    });
                }
            });
        }
        wrapper.orderByAsc(QuestionBankItemEntity::getQuestionTitle);
        return questionMapper.selectList(wrapper).stream()
                // Keep enough rows for the frontend's explicit page controls; the service applies strict topic
                // filtering and BGE reranking before the UI slices the result set.
                .limit(Math.max(1, Math.min(KnowledgeQuestionBankService.MAX_SEARCH_ROWS, limit)))
                .map(entity -> toRecord(entity, links(tenantId, entity.getQuestionId())))
                .toList();
    }

    /**
     * Splits user search text into bounded non-blank terms so "双曲线 大题" does not become one exact LIKE.
     */
    private static List<String> searchKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(query.strip().split("[\\s,，、]+"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(6)
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
     * Converts a relation record to entity.
     */
    private static KnowledgeRelationEntity toEntity(KnowledgeRelationRecord record) {
        KnowledgeRelationEntity entity = new KnowledgeRelationEntity();
        entity.setRelationId(record.relationId());
        entity.setTenantId(record.tenantId());
        entity.setSourceKnowledgePointId(record.sourceKnowledgePointId());
        entity.setTargetKnowledgePointId(record.targetKnowledgePointId());
        entity.setRelationType(record.relationType());
        entity.setEvidenceSummary(record.evidenceSummary());
        entity.setStatus(record.status());
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
        entity.setSourceResourceDocumentId(record.sourceResourceDocumentId());
        entity.setSourceBlockId(record.sourceBlockId());
        entity.setSourceChecksum(record.sourceChecksum());
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
     * Converts relation entity to service record.
     */
    private static KnowledgeRelationRecord toRecord(KnowledgeRelationEntity entity) {
        return new KnowledgeRelationRecord(
                entity.getRelationId(),
                entity.getTenantId(),
                entity.getSourceKnowledgePointId(),
                entity.getTargetKnowledgePointId(),
                entity.getRelationType(),
                entity.getEvidenceSummary(),
                entity.getStatus());
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
                entity.getSourceResourceDocumentId(),
                entity.getSourceBlockId(),
                entity.getSourceChecksum(),
                knowledgePointIds);
    }

    private static String sourceKey(String sourceBlockId, String sourceChecksum) {
        return (sourceBlockId == null ? "" : sourceBlockId) + "\n" + (sourceChecksum == null ? "" : sourceChecksum);
    }
}
