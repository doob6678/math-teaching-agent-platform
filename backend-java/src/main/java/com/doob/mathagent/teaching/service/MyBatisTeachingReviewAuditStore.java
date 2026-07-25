package com.doob.mathagent.teaching.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.teaching.entity.TeachingReviewAuditEntity;
import com.doob.mathagent.teaching.mapper.TeachingReviewAuditMapper;
import com.doob.mathagent.teaching.vo.TeachingReviewAuditResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** MySQL implementation; rows are inserted only and never updated or deleted by this service. */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeachingReviewAuditStore implements TeachingReviewAuditStore {
    private final TeachingReviewAuditMapper mapper;
    public MyBatisTeachingReviewAuditStore(TeachingReviewAuditMapper mapper) { this.mapper = mapper; }
    @Override public TeachingReviewAuditResponse append(TeachingReviewAuditResponse audit) {
        mapper.insert(toEntity(audit));
        return audit;
    }
    @Override public List<TeachingReviewAuditResponse> list(String taskId) {
        return mapper.selectList(new LambdaQueryWrapper<TeachingReviewAuditEntity>()
                        .eq(TeachingReviewAuditEntity::getTaskId, taskId)
                        .orderByAsc(TeachingReviewAuditEntity::getCreatedAt))
                .stream().map(MyBatisTeachingReviewAuditStore::toResponse).toList();
    }
    private static TeachingReviewAuditEntity toEntity(TeachingReviewAuditResponse a) {
        TeachingReviewAuditEntity e = new TeachingReviewAuditEntity();
        e.setReviewAuditId(a.reviewAuditId()); e.setTaskId(a.taskId()); e.setTenantId(a.tenantId());
        e.setReviewerSubjectType(a.reviewerSubjectType()); e.setReviewerSubjectId(a.reviewerSubjectId());
        e.setPolicyCode(a.policyCode()); e.setDecisionCode(a.decisionCode()); e.setReasonText(a.reasonText());
        e.setCommonDraftHash(a.commonDraftHash()); e.setQualityStatus(a.qualityStatus());
        e.setTeacherVersionHash(a.teacherVersionHash()); e.setStudentVersionHash(a.studentVersionHash());
        e.setLectureVersionHash(a.lectureVersionHash()); e.setPublishedStatus(a.publishedStatus()); e.setCreatedAt(a.createdAt());
        return e;
    }
    private static TeachingReviewAuditResponse toResponse(TeachingReviewAuditEntity e) {
        return new TeachingReviewAuditResponse(e.getReviewAuditId(), e.getTaskId(), e.getTenantId(),
                e.getReviewerSubjectType(), e.getReviewerSubjectId(), e.getPolicyCode(), e.getDecisionCode(),
                e.getReasonText(), e.getCommonDraftHash(), e.getQualityStatus(), e.getTeacherVersionHash(),
                e.getStudentVersionHash(), e.getLectureVersionHash(), e.getPublishedStatus(), e.getCreatedAt());
    }
}
