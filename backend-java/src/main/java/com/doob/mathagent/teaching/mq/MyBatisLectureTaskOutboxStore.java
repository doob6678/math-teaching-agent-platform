package com.doob.mathagent.teaching.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.teaching.entity.LectureTaskOutboxEventEntity;
import com.doob.mathagent.teaching.mapper.LectureTaskOutboxEventMapper;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * MySQL outbox adapter.
 *
 * <p>The initial create event is idempotent by deterministic primary key. Resume events deliberately receive a new
 * primary key because one durable task can fail and be manually resumed more than once.</p>
 */
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
        String normalizedEventType = requireText(eventType, "Lecture outbox event type is required");
        event.setEventId(eventIdFor(taskId, normalizedEventType)); event.setTaskId(taskId); event.setEventType(normalizedEventType); event.setStatus(PENDING); event.setCreatedAt(Instant.now());
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException exception) {
            if (!CREATED.equals(normalizedEventType)) {
                // A resume must never disappear behind the idempotency rule reserved for the initial create event.
                throw exception;
            }
        }
    }
    @Override public List<LectureTaskOutboxEvent> findPending(int limit) { return mapper.selectList(new LambdaQueryWrapper<LectureTaskOutboxEventEntity>().eq(LectureTaskOutboxEventEntity::getStatus, PENDING).orderByAsc(LectureTaskOutboxEventEntity::getCreatedAt).last("LIMIT " + Math.max(1, limit))).stream().map(row -> new LectureTaskOutboxEvent(row.getEventId(), row.getTaskId(), row.getCreatedAt())).toList(); }
    @Override public void markPublished(String eventId) { mapper.update(null, new LambdaUpdateWrapper<LectureTaskOutboxEventEntity>().eq(LectureTaskOutboxEventEntity::getEventId, eventId).eq(LectureTaskOutboxEventEntity::getStatus, PENDING).set(LectureTaskOutboxEventEntity::getStatus, "PUBLISHED").set(LectureTaskOutboxEventEntity::getPublishedAt, Instant.now())); }

    /** Uses one stable create-event key while every retry/resume remains an independently publishable fact. */
    static String eventIdFor(String taskId, String eventType) {
        String normalizedTaskId = requireText(taskId, "Lecture task id is required");
        String normalizedEventType = requireText(eventType, "Lecture outbox event type is required");
        if (CREATED.equals(normalizedEventType)) {
            return UUID.nameUUIDFromBytes((normalizedTaskId + ":" + normalizedEventType)
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
        return UUID.randomUUID().toString();
    }

    /** Rejects blank durable identities before they reach the outbox table. */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
