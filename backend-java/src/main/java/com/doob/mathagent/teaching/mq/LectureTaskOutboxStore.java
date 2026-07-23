package com.doob.mathagent.teaching.mq;

import java.util.List;

/** Persistence boundary for events written in the same transaction as a teaching task. */
public interface LectureTaskOutboxStore {
    void enqueue(String taskId);
    default void enqueue(String taskId, String eventType) { enqueue(taskId); }
    List<LectureTaskOutboxEvent> findPending(int limit);
    void markPublished(String eventId);
}
