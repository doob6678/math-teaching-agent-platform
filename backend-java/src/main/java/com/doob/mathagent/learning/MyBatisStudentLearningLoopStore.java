package com.doob.mathagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.learning.entity.StudentKnowledgeMasteryEntity;
import com.doob.mathagent.learning.entity.StudentLearningAttemptEntity;
import com.doob.mathagent.learning.mapper.StudentKnowledgeMasteryMapper;
import com.doob.mathagent.learning.mapper.StudentLearningAttemptMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** MySQL implementation: attempts are immutable and the mastery row is an updatable projection. */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisStudentLearningLoopStore implements StudentLearningLoopStore {
    private final StudentLearningAttemptMapper attemptMapper;
    private final StudentKnowledgeMasteryMapper masteryMapper;
    private final ObjectMapper objectMapper;
    public MyBatisStudentLearningLoopStore(StudentLearningAttemptMapper attemptMapper, StudentKnowledgeMasteryMapper masteryMapper,
            ObjectMapper objectMapper) { this.attemptMapper=attemptMapper; this.masteryMapper=masteryMapper; this.objectMapper=objectMapper; }
    @Override public StudentLearningAttempt saveAttempt(StudentLearningAttempt value) {
        StudentLearningAttemptEntity entity=new StudentLearningAttemptEntity(); entity.setAttemptId(value.attemptId()); entity.setTenantId(value.tenantId());
        entity.setStudentId(value.studentId()); entity.setQuestionId(value.questionId()); entity.setQuestionText(value.questionText());
        entity.setKnowledgePointIdsJson(json(value.knowledgePointIds())); entity.setCorrect(value.correct()); entity.setResponseTimeMs(value.responseTimeMs());
        entity.setSubmittedAt(LocalDateTime.ofInstant(value.submittedAt(), ZoneOffset.UTC)); attemptMapper.insert(entity); return value;
    }
    @Override public List<StudentLearningAttempt> findAttempts(String tenantId,String studentId,String point) {
        return attemptMapper.selectList(new LambdaQueryWrapper<StudentLearningAttemptEntity>().eq(StudentLearningAttemptEntity::getTenantId,tenantId)
                .eq(StudentLearningAttemptEntity::getStudentId,studentId).orderByDesc(StudentLearningAttemptEntity::getSubmittedAt)).stream()
                .map(this::attempt).filter(value -> value.knowledgePointIds().contains(point)).toList();
    }
    @Override public StudentKnowledgeMastery saveMastery(StudentKnowledgeMastery value) {
        LambdaQueryWrapper<StudentKnowledgeMasteryEntity> query=new LambdaQueryWrapper<StudentKnowledgeMasteryEntity>()
                .eq(StudentKnowledgeMasteryEntity::getTenantId,value.tenantId()).eq(StudentKnowledgeMasteryEntity::getStudentId,value.studentId())
                .eq(StudentKnowledgeMasteryEntity::getKnowledgePointId,value.knowledgePointId());
        StudentKnowledgeMasteryEntity entity=masteryEntity(value); StudentKnowledgeMasteryEntity existing=masteryMapper.selectOne(query);
        if(existing==null) masteryMapper.insert(entity); else { entity.setTenantId(existing.getTenantId()); entity.setStudentId(existing.getStudentId()); entity.setKnowledgePointId(existing.getKnowledgePointId()); masteryMapper.update(entity,query); }
        return value;
    }
    @Override public List<StudentKnowledgeMastery> findMastery(String tenantId,String studentId) { return masteryMapper.selectList(new LambdaQueryWrapper<StudentKnowledgeMasteryEntity>()
            .eq(StudentKnowledgeMasteryEntity::getTenantId,tenantId).eq(StudentKnowledgeMasteryEntity::getStudentId,studentId)
            .orderByAsc(StudentKnowledgeMasteryEntity::getMasteryPercent)).stream().map(this::mastery).toList(); }
    @Override public List<StudentKnowledgeMastery> findTenantMastery(String tenantId,String studentId) {
        LambdaQueryWrapper<StudentKnowledgeMasteryEntity> query=new LambdaQueryWrapper<StudentKnowledgeMasteryEntity>().eq(StudentKnowledgeMasteryEntity::getTenantId,tenantId)
                .orderByAsc(StudentKnowledgeMasteryEntity::getMasteryPercent); if(studentId!=null&&!studentId.isBlank()) query.eq(StudentKnowledgeMasteryEntity::getStudentId,studentId);
        return masteryMapper.selectList(query).stream().map(this::mastery).toList();
    }
    private StudentLearningAttempt attempt(StudentLearningAttemptEntity e) { return new StudentLearningAttempt(e.getAttemptId(),e.getTenantId(),e.getStudentId(),e.getQuestionId(),e.getQuestionText(),
            strings(e.getKnowledgePointIdsJson()),Boolean.TRUE.equals(e.getCorrect()),e.getResponseTimeMs()==null?0L:e.getResponseTimeMs(),e.getSubmittedAt().toInstant(ZoneOffset.UTC)); }
    private StudentKnowledgeMasteryEntity masteryEntity(StudentKnowledgeMastery v) { StudentKnowledgeMasteryEntity e=new StudentKnowledgeMasteryEntity(); e.setTenantId(v.tenantId());e.setStudentId(v.studentId());e.setKnowledgePointId(v.knowledgePointId());e.setMasteryPercent(v.masteryPercent());e.setAttemptCount(v.attemptCount());e.setCorrectCount(v.correctCount());e.setIncorrectCount(v.incorrectCount());e.setWeaknessLevel(v.weaknessLevel());e.setLastAttemptAt(v.lastAttemptAt()==null?null:LocalDateTime.ofInstant(v.lastAttemptAt(),ZoneOffset.UTC));e.setEvidenceSummary(v.evidenceSummary());return e; }
    private StudentKnowledgeMastery mastery(StudentKnowledgeMasteryEntity e) { return new StudentKnowledgeMastery(e.getTenantId(),e.getStudentId(),e.getKnowledgePointId(),e.getMasteryPercent(),e.getAttemptCount(),e.getCorrectCount(),e.getIncorrectCount(),e.getWeaknessLevel(),e.getLastAttemptAt()==null?null:e.getLastAttemptAt().toInstant(ZoneOffset.UTC),e.getEvidenceSummary()); }
    private String json(List<String> value) { try{return objectMapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot serialize knowledge point ids",e);} }
    private List<String> strings(String value) { try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Cannot read persisted knowledge point ids",e);} }
}
