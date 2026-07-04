package com.doob.mathagent.teaching.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.teaching.entity.TeachingHumanFeedbackEntity;
import com.doob.mathagent.teaching.mapper.TeachingHumanFeedbackMapper;
import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed human feedback store for teaching task review loops.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeachingHumanFeedbackStore implements TeachingHumanFeedbackStore {

    private final TeachingHumanFeedbackMapper mapper;

    public MyBatisTeachingHumanFeedbackStore(TeachingHumanFeedbackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TeachingHumanFeedbackResponse save(String ownerKey, TeachingHumanFeedbackResponse feedback) {
        mapper.insert(toEntity(ownerKey, feedback));
        return feedback;
    }

    @Override
    public List<TeachingHumanFeedbackResponse> list(String ownerKey, String taskId) {
        return mapper.selectList(new LambdaQueryWrapper<TeachingHumanFeedbackEntity>()
                        .eq(TeachingHumanFeedbackEntity::getOwnerKey, ownerKey)
                        .eq(TeachingHumanFeedbackEntity::getTaskId, taskId)
                        .orderByAsc(TeachingHumanFeedbackEntity::getCreatedAt))
                .stream()
                .map(MyBatisTeachingHumanFeedbackStore::toResponse)
                .toList();
    }

    private static TeachingHumanFeedbackEntity toEntity(String ownerKey, TeachingHumanFeedbackResponse feedback) {
        TeachingHumanFeedbackEntity entity = new TeachingHumanFeedbackEntity();
        entity.setFeedbackId(feedback.feedbackId());
        entity.setTaskId(feedback.taskId());
        entity.setTenantId(feedback.tenantId());
        entity.setSubjectType(feedback.subjectType());
        entity.setSubjectId(feedback.subjectId());
        entity.setOwnerKey(ownerKey);
        entity.setRating(feedback.rating());
        entity.setDecision(feedback.decision());
        entity.setComment(feedback.comment());
        entity.setCreatedAt(feedback.createdAt());
        return entity;
    }

    private static TeachingHumanFeedbackResponse toResponse(TeachingHumanFeedbackEntity entity) {
        return new TeachingHumanFeedbackResponse(
                entity.getFeedbackId(),
                entity.getTaskId(),
                entity.getTenantId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getRating(),
                entity.getDecision(),
                entity.getComment(),
                entity.getCreatedAt());
    }
}
