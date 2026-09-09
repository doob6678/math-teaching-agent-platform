package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.entity.StudentExplanationWorkflowEventEntity;
import com.doob.mathagent.student.mapper.StudentExplanationWorkflowEventMapper;
import com.doob.mathagent.student.service.StudentExplanationWorkflowEventRedisGateway.BufferedEvent;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Write-behind decorator that keeps hot-path SSE event inserts off the MySQL pool.
 *
 * <p>Motivation: the student SSE stream persists one {@code student_explanation_workflow_event} row per public
 * event, and {@code ai_delta} events fire per provider chunk — hundreds of inserts per answer, dozens per second,
 * each borrowing one of the 25 Hikari connections. Buffered events instead take a single Redis EVAL per append
 * (zero database connections) and are drained in batches: one short transaction per run per round borrows and
 * returns a connection far less often.</p>
 *
 * <p>Cursor-id authority: ids come from one Redis sequence seeded to {@code MAX(event_id)} at boot and never allowed
 * below the in-memory high-water mark. The same explicit-id path is used for the degraded direct writes, so buffered
 * and direct-written ids share one monotonically increasing space and Last-Event-ID replay stays ordered no matter
 * which path produced an event. {@code complete}/{@code fail} and terminal events are never left in the buffer:
 * they flush through synchronously, so a reconnecting client can always reach a terminal event.</p>
 *
 * <p>Degradation: any Redis failure (append, read or flush) is caught; appends fall back to direct MySQL writes and
 * reads serve only flushed rows. The API never sees a Redis error, and buffered events stay in Redis (TTL-bounded)
 * for the next flush round instead of being dropped. A JVM crash alone loses nothing — the boot-time round plus the
 * surviving Redis buffer get drained; only a simultaneous Redis data loss inside the flush window drops events, and
 * that degrades mid-run reconnect text, never the terminal answer or history.</p>
 *
 * <p>Single-instance assumption: same as the rest of this backend, sequence allocation and buffer draining are safe
 * for one writer process per key namespace; a multi-instance deployment must shard run ids before enabling.</p>
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "math-agent.redis.student-explanation-write-behind", name = "enabled", havingValue = "true")
public class RedisWriteBehindStudentExplanationWorkflowStore implements StudentExplanationWorkflowStore {

    private static final Logger log = LoggerFactory.getLogger(RedisWriteBehindStudentExplanationWorkflowStore.class);
    /** Terminal events must be durable before the client may rely on them for replay termination, so they bypass the buffer. */
    private static final Set<String> TERMINAL_EVENTS = Set.of("completed", "error");
    /** Rate-limit for repeated degradation WARNs so a dead Redis cannot flood the log on every per-chunk append. */
    private static final long WARN_COOLDOWN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_INSERT_ID_RETRIES = 3;

    private final MyBatisStudentExplanationWorkflowStore delegate;
    private final StudentExplanationWorkflowEventRedisGateway gateway;
    private final StudentExplanationWorkflowEventMapper eventMapper;
    private final TransactionOperations transactionOperations;
    private final ObjectMapper objectMapper;
    private final StudentExplanationWriteBehindProperties properties;

    /** Highest event id ever issued by this process (buffered or direct); reseeds the Redis sequence after outages. */
    private final AtomicLong highWater = new AtomicLong(0L);
    private final AtomicLong lastDegradeWarnAt = new AtomicLong(0L);
    private volatile boolean redisDegraded;

    public RedisWriteBehindStudentExplanationWorkflowStore(
            MyBatisStudentExplanationWorkflowStore delegate,
            StudentExplanationWorkflowEventRedisGateway gateway,
            StudentExplanationWorkflowEventMapper eventMapper,
            TransactionOperations transactionOperations,
            ObjectMapper objectMapper,
            StudentExplanationWriteBehindProperties properties) {
        this.delegate = delegate;
        this.gateway = gateway;
        this.eventMapper = eventMapper;
        this.transactionOperations = transactionOperations;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Seeds the id high-water from MySQL so application-assigned ids start above every already-durable row, then
     * drains any buffer a previous JVM crash left behind before the first new event can be appended.
     */
    @PostConstruct
    public void initialize() {
        highWater.set(eventMapper.selectMaxEventId());
        flushPendingEvents();
    }

    @Override
    public WorkflowRun createOrLoad(RequestSubject subject, StudentExplanationRequest request) {
        // One insert per student submission and the FK target for every event row: always durable, always first.
        return delegate.createOrLoad(subject, request);
    }

    @Override
    public WorkflowEvent append(String runId, String eventName, StudentExplanationStreamEvent event) {
        String eventJson = json(event);
        if (TERMINAL_EVENTS.contains(eventName)) {
            // Drain what is buffered so the terminal row is written last in id order and replay can terminate;
            // a Redis failure here must not delay or fail the terminal event, hence best effort.
            flushRunQuietly(runId);
            return writeThrough(runId, eventName, event, eventJson);
        }
        try {
            long eventId = gateway.append(runId, eventName, eventJson, highWater.get());
            if (eventId == StudentExplanationWorkflowEventRedisGateway.BUFFER_FULL) {
                warnDegraded("buffer_capped runId=" + runId, null);
                return writeThrough(runId, eventName, event, eventJson);
            }
            highWater.updateAndGet(current -> Math.max(current, eventId));
            markRecovered();
            return new WorkflowEvent(eventId, eventName, event);
        } catch (RuntimeException exception) {
            warnDegraded("append_fallback runId=" + runId, exception);
            return writeThrough(runId, eventName, event, eventJson);
        }
    }

    @Override
    public List<WorkflowEvent> eventsAfter(String runId, long afterEventId, int limit) {
        List<WorkflowEvent> flushed = delegate.eventsAfter(runId, afterEventId, limit);
        List<BufferedEvent> buffered;
        try {
            buffered = gateway.readAfter(runId, afterEventId, limit);
        } catch (RuntimeException exception) {
            warnDegraded("read_fallback runId=" + runId, exception);
            return flushed;
        }
        if (buffered.isEmpty()) {
            return flushed;
        }
        Set<Long> seen = new HashSet<>();
        List<WorkflowEvent> merged = new ArrayList<>(flushed.size() + buffered.size());
        for (WorkflowEvent event : flushed) {
            if (event.eventId() > afterEventId && seen.add(event.eventId())) {
                merged.add(event);
            }
        }
        for (BufferedEvent event : buffered) {
            if (event.eventId() > afterEventId && seen.add(event.eventId())) {
                merged.add(new WorkflowEvent(event.eventId(), event.eventName(),
                        read(event.eventJson(), StudentExplanationStreamEvent.class)));
            }
        }
        // Direct-written events can hold ids above still-buffered ones, so the merged page must be re-sorted.
        merged.sort(Comparator.comparingLong(WorkflowEvent::eventId));
        return merged.size() > limit ? List.copyOf(merged.subList(0, limit)) : merged;
    }

    @Override
    public void complete(String runId, StudentExplanationResponse response) {
        delegate.complete(runId, response);
    }

    @Override
    public void fail(String runId, String errorCode, String errorMessage) {
        delegate.fail(runId, errorCode, errorMessage);
    }

    /**
     * Batch drain of every active run's Redis buffer into MySQL. Runs on the shared scheduler between rounds and
     * once at boot; a MySQL failure keeps the buffer untouched (the insert transaction rolls back before any LTRIM),
     * so the next round retries the same events.
     */
    @Scheduled(fixedDelayString = "${math-agent.redis.student-explanation-write-behind.flush-interval-ms:500}")
    public synchronized void flushPendingEvents() {
        Set<String> runs;
        try {
            runs = gateway.activeRuns();
        } catch (RuntimeException exception) {
            warnDegraded("flush_scan_skipped", exception);
            return;
        }
        markRecovered();
        for (String runId : runs) {
            try {
                flushRun(runId);
            } catch (RuntimeException exception) {
                log.warn("student_explanation_write_behind_flush_failed runId={} type={} message={}",
                        runId, exception.getClass().getSimpleName(), exception.getMessage());
            }
        }
    }

    /** Durability before shutdown: the buffer would survive in Redis anyway, this empties it one round earlier. */
    @PreDestroy
    public synchronized void flushBeforeShutdown() {
        try {
            flushPendingEvents();
        } catch (RuntimeException exception) {
            log.warn("student_explanation_write_behind_shutdown_flush_failed type={} message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    /** Drains one run's buffer in {@code batch-size} slices; capped so a pathological backlog yields to the scheduler. */
    private void flushRun(String runId) {
        int batchSize = properties.normalizedBatchSize();
        int maxRounds = properties.normalizedMaxBufferedEvents() / batchSize + 2;
        for (int round = 0; round < maxRounds; round += 1) {
            List<BufferedEvent> batch = gateway.peekOldest(runId, batchSize);
            if (batch.isEmpty()) {
                // Empty list: confirm prunes the run from the active index even when the last event arrived and
                // drained between activeRuns() and here.
                gateway.confirmFlushed(runId, 0);
                return;
            }
            // One transaction per batch: every insert shares a single borrowed connection, the pool sees one
            // checkout per batch instead of one per event.
            transactionOperations.executeWithoutResult(status -> batch.forEach(entry -> insertDurable(runId, entry)));
            long remaining = gateway.confirmFlushed(runId, batch.size());
            if (batch.size() < batchSize || remaining <= 0L) {
                return;
            }
        }
        log.warn("student_explanation_write_behind_flush_backlog runId={} hint=\"MySQL lagging; buffer keeps draining next round\"",
                runId);
    }

    private void flushRunQuietly(String runId) {
        try {
            flushRun(runId);
        } catch (RuntimeException exception) {
            warnDegraded("terminal_preflush_failed runId=" + runId, exception);
        }
    }

    private void insertDurable(String runId, BufferedEvent entry) {
        StudentExplanationWorkflowEventEntity entity = new StudentExplanationWorkflowEventEntity();
        entity.setEventId(entry.eventId());
        entity.setRunId(runId);
        entity.setEventName(entry.eventName());
        entity.setEventJson(entry.eventJson());
        try {
            eventMapper.insertWithExplicitId(entity);
        } catch (DuplicateKeyException duplicate) {
            if (isSameDurableEvent(runId, entry, duplicate)) {
                return;
            }
            throw duplicate;
        }
    }

    /** Degraded or crash-retry path: an explicit id already present is a replay of the very same row, not data loss. */
    private boolean isSameDurableEvent(String runId, BufferedEvent entry, DuplicateKeyException duplicate) {
        StudentExplanationWorkflowEventEntity existing = eventMapper.selectById(entry.eventId());
        if (existing != null && runId.equals(existing.getRunId()) && entry.eventJson().equals(existing.getEventJson())) {
            return true;
        }
        // A foreign row on this id means the single-writer id-space assumption broke (e.g. an unsharded second
        // instance); surface it rather than silently dropping content.
        log.error("student_explanation_write_behind_id_collision eventId={} runId={} duplicate={}",
                entry.eventId(), runId, duplicate.getMessage());
        return false;
    }

    private WorkflowEvent writeThrough(String runId, String eventName, StudentExplanationStreamEvent event, String eventJson) {
        DuplicateKeyException lastDuplicate = null;
        for (int attempt = 0; attempt < MAX_INSERT_ID_RETRIES; attempt += 1) {
            long eventId = highWater.incrementAndGet();
            StudentExplanationWorkflowEventEntity entity = new StudentExplanationWorkflowEventEntity();
            entity.setEventId(eventId);
            entity.setRunId(runId);
            entity.setEventName(eventName);
            entity.setEventJson(eventJson);
            try {
                eventMapper.insertWithExplicitId(entity);
                return new WorkflowEvent(eventId, eventName, event);
            } catch (DuplicateKeyException duplicate) {
                lastDuplicate = duplicate;
            }
        }
        throw lastDuplicate == null ? new IllegalStateException("write-through loop exited without an id") : lastDuplicate;
    }

    private void warnDegraded(String stage, RuntimeException exception) {
        long now = System.nanoTime();
        long previous = lastDegradeWarnAt.get();
        redisDegraded = true;
        if (now - previous >= WARN_COOLDOWN_NANOS && lastDegradeWarnAt.compareAndSet(previous, now)) {
            log.warn("student_explanation_write_behind_degraded stage={} redisUnavailable=true "
                            + "action=\"direct MySQL writes; buffered events retry on next flush round\" type={} message={}",
                    stage, exception == null ? "" : exception.getClass().getSimpleName(),
                    exception == null ? "" : exception.getMessage());
        }
    }

    private void markRecovered() {
        if (redisDegraded) {
            redisDegraded = false;
            log.info("student_explanation_write_behind_recovered");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Student explanation workflow serialization failed", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Student explanation workflow data is invalid", exception);
        }
    }
}
