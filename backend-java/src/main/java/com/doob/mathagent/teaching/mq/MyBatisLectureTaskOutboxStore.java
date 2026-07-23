package com.doob.mathagent.teaching.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.teaching.entity.LectureTaskOutboxEventEntity;
import com.doob.mathagent.teaching.mapper.LectureTaskOutboxEventMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** MySQL outbox adapter. The unique task/event key makes concurrent submits harmless. */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisLectureTaskOutboxStore implements LectureTaskOutboxStore {
    private static final String CREATED = "LECTURE_TASK_CREATED";
    private static final String PENDING = "PENDING";
    private final LectureTaskOutboxEventMapper mapper;
    public MyBatisLectureTaskOutboxStore(LectureTaskOutboxEventMapper mapper) { this.mapper = mapper; }
    @Override public void enqueue(String taskId) { enqueue(taskId, CREATED); }
    @Override public void enqueue(String taskId, String eventType) {
        LectureTaskOutboxEventEntity event = new LectureTaskOutboxEventEntity();
        event.setEventId(UUID.randomUUID().toString()); event.setTaskId(taskId); event.setEventType(eventType); event.setStatus(PENDING); event.setCreatedAt(Instant.now());
        try { mapper.insert(event); } catch (DuplicateKeyException ignored) { /* same durable task already has its event */ }
    }
    @Override public List<LectureTaskOutboxEvent> findPending(int limit) { return mapper.selectList(new LambdaQueryWrapper<LectureTaskOutboxEventEntity>().eq(LectureTaskOutboxEventEntity::getStatus, PENDING).orderByAsc(LectureTaskOutboxEventEntity::getCreatedAt).last("LIMIT " + Math.max(1, limit))).stream().map(row -> new LectureTaskOutboxEvent(row.getEventId(), row.getTaskId(), row.getCreatedAt())).toList(); }
    @Override public void markPublished(String eventId) { mapper.update(null, new LambdaUpdateWrapper<LectureTaskOutboxEventEntity>().eq(LectureTaskOutboxEventEntity::getEventId, eventId).eq(LectureTaskOutboxEventEntity::getStatus, PENDING).set(LectureTaskOutboxEventEntity::getStatus, "PUBLISHED").set(LectureTaskOutboxEventEntity::getPublishedAt, Instant.now())); }
}
