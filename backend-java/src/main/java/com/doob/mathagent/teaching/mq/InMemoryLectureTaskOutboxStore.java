package com.doob.mathagent.teaching.mq;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Small deterministic adapter used by focused state-machine tests. */
public class InMemoryLectureTaskOutboxStore implements LectureTaskOutboxStore {
    private final List<LectureTaskOutboxEvent> pending = new ArrayList<>();
    private final List<LectureTaskOutboxEvent> published = new ArrayList<>();

    @Override public synchronized void enqueue(String taskId) { enqueue(taskId, "LECTURE_TASK_CREATED"); }
    @Override public synchronized void enqueue(String taskId, String eventType) { pending.add(new LectureTaskOutboxEvent(UUID.randomUUID().toString(), taskId, Instant.now())); }
    @Override public synchronized List<LectureTaskOutboxEvent> findPending(int limit) { return pending.stream().sorted(Comparator.comparing(LectureTaskOutboxEvent::createdAt)).limit(limit).toList(); }
    @Override public synchronized void markPublished(String eventId) { pending.stream().filter(event -> event.eventId().equals(eventId)).findFirst().ifPresent(event -> { pending.remove(event); published.add(event); }); }
    public synchronized List<LectureTaskOutboxEvent> pending() { return List.copyOf(pending); }
    public synchronized List<LectureTaskOutboxEvent> published() { return List.copyOf(published); }
}
