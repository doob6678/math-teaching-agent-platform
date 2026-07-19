package com.doob.mathagent.teaching.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.teaching.entity.TeachingHumanFeedbackEntity;
import com.doob.mathagent.teaching.mapper.TeachingHumanFeedbackMapper;
import com.doob.mathagent.teaching.vo.TeachingHumanFeedbackResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed human feedback store for teaching task review loops.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeachingHumanFeedbackStore implements TeachingHumanFeedbackStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> REVIEW_CONTEXT_TYPE = new TypeReference<>() {
    };

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
        entity.setReviewContextJson(toJson(feedback.reviewContext()));
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
                fromJson(entity.getReviewContextJson()),
                entity.getCreatedAt());
    }

    private static String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (RuntimeException | java.io.IOException exception) {
            return "{}";
        }
    }

    private static Map<String, Object> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(value, REVIEW_CONTEXT_TYPE);
        } catch (RuntimeException | java.io.IOException exception) {
            return Map.of();
        }
    }
}
