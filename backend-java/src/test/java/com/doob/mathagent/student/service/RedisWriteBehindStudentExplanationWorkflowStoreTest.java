package com.doob.mathagent.student.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.entity.StudentExplanationWorkflowEventEntity;
import com.doob.mathagent.student.mapper.StudentExplanationWorkflowEventMapper;
import com.doob.mathagent.student.service.StudentExplanationWorkflowEventRedisGateway.BufferedEvent;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Covers the Redis write-behind decorator for student SSE events: buffered appends drain in batched MySQL rounds,
 * terminal events stay synchronous, every degradation path (Redis down, buffer cap, MySQL failure mid-batch) keeps
 * data durable without surfacing an error to the stream, and event ids stay strictly monotonic per run no matter
 * which write path produced them. The Redis gateway and MyBatis mapper fakes are hand-written because the project
 * keeps the test classpath dependency-free (no Mockito); the mapper fake mimics AUTO-counter replay semantics and
 * the transaction fake mimics rollback so retry-idempotency is exercised for real.
 */
class RedisWriteBehindStudentExplanationWorkflowStoreTest {

    private static final String RUN = "11111111-2222-3333-4444-555555555555";

    private ObjectMapper objectMapper;
    private FakeGateway gateway;
    private FakeMapper mapper;
    private FakeTransaction transactions;
    private FakeMyBatisStore delegate;
    private RedisWriteBehindStudentExplanationWorkflowStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        gateway = new FakeGateway();
        mapper = new FakeMapper();
        transactions = new FakeTransaction(mapper);
        delegate = new FakeMyBatisStore();
        store = store(2, 100);
    }

    private RedisWriteBehindStudentExplanationWorkflowStore store(int batchSize, int maxBuffered) {
        gateway.capacity = maxBuffered;
        StudentExplanationWriteBehindProperties properties = new StudentExplanationWriteBehindProperties(
                true, "test:wb", batchSize, Duration.ofHours(1), maxBuffered);
        RedisWriteBehindStudentExplanationWorkflowStore target =
                new RedisWriteBehindStudentExplanationWorkflowStore(delegate, gateway, mapper.proxy(),
                        transactions, objectMapper, properties);
        target.initialize();
        return target;
    }

    @Test
    void appendBuffersInRedisAndFlushPersistsExplicitIdsInOrder() {
        long first = store.append(RUN, "progress", event("progress", "第一步")).eventId();
        long second = store.append(RUN, "ai_delta", event("ai_delta", "A")).eventId();
        // Hot path: nothing touched MySQL yet, ids came from the Redis sequence seeded at MAX(event_id)=100.
        assertThat(mapper.rows).isEmpty();
        assertThat(gateway.size(RUN)).isEqualTo(2);
        assertThat(first).isEqualTo(101L);
        assertThat(second).isEqualTo(102L);

        store.flushPendingEvents();

        assertThat(List.copyOf(mapper.rows.keySet())).containsExactly(101L, 102L);
        assertThat(mapper.rows.get(101L).getRunId()).isEqualTo(RUN);
        assertThat(mapper.rows.get(101L).getEventName()).isEqualTo("progress");
        assertThat(gateway.size(RUN)).isZero();
        assertThat(gateway.activeRuns()).isEmpty();
    }

    @Test
    void flushDrainsBacklogInBatchSlicesWithOneTransactionPerBatch() {
        for (int index = 0; index < 5; index += 1) {
            store.append(RUN, "ai_delta", event("ai_delta", "x" + index));
        }
        transactions.rounds = 0;

        store.flushPendingEvents();

        // batch-size 2 → five buffered events drain as 2 + 2 + 1 = three MySQL transactions, not five round-trip storms.
        assertThat(transactions.rounds).isEqualTo(3);
        assertThat(List.copyOf(mapper.rows.keySet())).containsExactly(101L, 102L, 103L, 104L, 105L);
    }

    @Test
    void terminalEventDrainsBufferThenWritesThroughSynchronously() {
        store.append(RUN, "ai_delta", event("ai_delta", "正文"));
        long terminal = store.append(RUN, "completed", event("completed", "完成")).eventId();

        // The terminal event must never sit in Redis: replay termination depends on it being durable immediately,
        // and its id must exceed the buffered delta that precedes it.
        assertThat(mapper.rows.keySet()).containsExactlyInAnyOrder(101L, 102L);
        assertThat(mapper.rows.get(102L).getEventName()).isEqualTo("completed");
        assertThat(terminal).isEqualTo(102L);
        assertThat(gateway.size(RUN)).isZero();
    }

    @Test
    void redisAppendFailureDegradesToDirectWriteAndSequenceReseedsAboveIt() {
        long buffered = store.append(RUN, "progress", event("progress", "p")).eventId();
        gateway.failAppends = true;
        long direct = store.append(RUN, "ai_delta", event("ai_delta", "d")).eventId();
        // Degradation is invisible to the caller: an id came back and the row is durable in MySQL.
        assertThat(direct).isGreaterThan(buffered);
        assertThat(mapper.rows).containsKey(direct);

        gateway.failAppends = false;
        long afterRecovery = store.append(RUN, "ai_delta", event("ai_delta", "r")).eventId();
        store.flushPendingEvents();

        // Mixed paths keep one ascending id space: recovery must reseed above the direct-write high-water.
        // (Insertion order in the fake map is not the write order; durability is what the id sort proves.)
        assertThat(afterRecovery).isGreaterThan(direct);
        assertThat(mapper.rows.keySet()).containsExactlyInAnyOrder(101L, 102L, 103L);
        // No user-visible error was raised anywhere along the degraded stretch.
        assertThat(store.eventsAfter(RUN, 0L, 100)).isEmpty();
    }

    @Test
    void bufferCapFallsBackToDirectWriteWithoutErrors() {
        RedisWriteBehindStudentExplanationWorkflowStore capped = store(2, 2);
        long first = capped.append(RUN, "ai_delta", event("ai_delta", "a")).eventId();
        long second = capped.append(RUN, "ai_delta", event("ai_delta", "b")).eventId();
        // List is at max-buffered-events: the third append must write through instead of growing Redis unboundedly.
        long third = capped.append(RUN, "ai_delta", event("ai_delta", "c")).eventId();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(mapper.rows).containsKey(third);
        assertThat(gateway.size(RUN)).isEqualTo(2);

        capped.flushPendingEvents();
        assertThat(mapper.rows.keySet()).containsExactlyInAnyOrder(101L, 102L, 103L);
    }

    @Test
    void eventsAfterMergesFlushedAndBufferedPagesWithoutDuplicates() {
        delegate.flushed = List.of(
                new StudentExplanationWorkflowStore.WorkflowEvent(5L, "progress", event("progress", "1")),
                new StudentExplanationWorkflowStore.WorkflowEvent(6L, "progress", event("progress", "2")));
        gateway.buffer(RUN, 6L, "progress", "{}"); // mid-flush race: id 6 lives in both layers
        gateway.buffer(RUN, 7L, "ai_delta", json(new StudentExplanationStreamEvent(
                "ai_delta", "3", null, null, null, null, "增量", null, List.of())));

        List<StudentExplanationWorkflowStore.WorkflowEvent> merged = store.eventsAfter(RUN, 4L, 100);

        assertThat(merged.stream().map(StudentExplanationWorkflowStore.WorkflowEvent::eventId).toList())
                .containsExactly(5L, 6L, 7L);
        assertThat(merged.get(2).event().aiContentDelta()).isEqualTo("增量");
    }

    @Test
    void eventsAfterFallsBackToMySQLOnlyWhenRedisReadFails() {
        delegate.flushed = List.of(new StudentExplanationWorkflowStore.WorkflowEvent(
                8L, "progress", event("progress", "x")));
        gateway.buffer(RUN, 9L, "ai_delta", "{}");
        gateway.failReads = true;

        assertThat(store.eventsAfter(RUN, 7L, 100))
                .extracting(StudentExplanationWorkflowStore.WorkflowEvent::eventId).containsExactly(8L);
    }

    @Test
    void mysqlFailureMidBatchRollsBackAndKeepsBufferForNextRound() {
        store.append(RUN, "ai_delta", event("ai_delta", "1"));
        store.append(RUN, "ai_delta", event("ai_delta", "2"));
        mapper.failInsert = new RuntimeException("simulated MySQL outage");

        store.flushPendingEvents();

        // Nothing half-persisted and nothing dropped: the buffer retries the same events after MySQL returns.
        assertThat(mapper.rows).isEmpty();
        assertThat(gateway.size(RUN)).isEqualTo(2);

        mapper.failInsert = null;
        store.flushPendingEvents();
        assertThat(List.copyOf(mapper.rows.keySet())).containsExactly(101L, 102L);
        assertThat(gateway.size(RUN)).isZero();
    }

    @Test
    void crashReplayBetweenInsertAndTrimIsIdempotent() {
        store.append(RUN, "ai_delta", event("ai_delta", "1"));
        // Simulate the crash window: the row is durable but the LTRIM never ran, so the buffer still holds it.
        mapper.rows.put(101L, row(101L, RUN, "ai_delta", gateway.peekOldest(RUN, 1).get(0).eventJson()));
        mapper.duplicateIds.add(101L);

        store.flushPendingEvents();

        assertThat(mapper.rows).hasSize(1);
        assertThat(gateway.size(RUN)).isZero();
    }

    @Test
    void foreignIdCollisionAbortsTheRoundWithoutTrimmingTheBuffer() {
        store.append(RUN, "ai_delta", event("ai_delta", "1"));
        // A different row already owns the id (single-writer assumption broken): the flush must not swallow it.
        mapper.rows.put(101L, row(101L, "other-run", "ai_delta", "{}"));
        mapper.duplicateIds.add(101L);

        store.flushPendingEvents();

        assertThat(gateway.size(RUN)).isEqualTo(1);
    }

    @Test
    void bootFlushDrainsLeftoverBufferFromPreviousProcess() {
        gateway.buffer(RUN, 101L, "ai_delta", json(event("ai_delta", "leftover")));
        gateway.buffer(RUN, 102L, "ai_delta", json(event("ai_delta", "leftover2")));
        mapper.maxEventId = 102L;

        // initialize() runs a boot flush after seeding, so leftovers land in MySQL before any new event is appended.
        RedisWriteBehindStudentExplanationWorkflowStore rebooted = store(200, 5000);
        assertThat(List.copyOf(mapper.rows.keySet())).containsExactly(101L, 102L);
        assertThat(gateway.size(RUN)).isZero();
        long next = rebooted.append(RUN, "ai_delta", event("ai_delta", "fresh")).eventId();
        assertThat(next).isEqualTo(103L);
    }

    @Test
    void shutdownFlushDrainsEverythingBeforeExit() {
        store.append(RUN, "ai_delta", event("ai_delta", "1"));
        store.append(RUN, "ai_delta", event("ai_delta", "2"));

        store.flushBeforeShutdown();

        assertThat(List.copyOf(mapper.rows.keySet())).containsExactly(101L, 102L);
    }

    @Test
    void runIdentityAndTerminalUpdatesAlwaysGoStraightToTheDelegate() {
        StudentExplanationRequest request = new StudentExplanationRequest(
                null, "问题", null, null, null, null, null, null, null, null, null);
        RequestSubject subject = new RequestSubject("default", "student", "s-1", "device-1");
        store.createOrLoad(subject, request);
        store.complete(RUN, null);
        store.fail(RUN, "STREAM_FAILED", "boom");

        assertThat(delegate.createOrLoads).isEqualTo(1);
        assertThat(delegate.completes).isEqualTo(1);
        assertThat(delegate.fails).isEqualTo(1);
    }

    @Test
    void idSequenceStaysStrictlyMonotonicAcrossMixedWritePaths() {
        RedisWriteBehindStudentExplanationWorkflowStore wide = store(2, 500);
        List<Long> issued = new ArrayList<>();
        for (int index = 0; index < 30; index += 1) {
            if (index % 4 == 3) {
                issued.add(wide.append(RUN, "progress", event("progress", "p" + index)).eventId());
            } else {
                gateway.failAppends = index % 7 == 5;
                issued.add(wide.append(RUN, "ai_delta", event("ai_delta", "d" + index)).eventId());
                gateway.failAppends = false;
            }
        }
        issued.add(wide.append(RUN, "completed", event("completed", "end")).eventId());
        wide.flushPendingEvents();

        for (int index = 1; index < issued.size(); index += 1) {
            assertThat(issued.get(index)).isGreaterThan(issued.get(index - 1));
        }
        List<Long> durableIds = new ArrayList<>(mapper.rows.keySet());
        durableIds.sort(Long::compare);
        // The durable id set is exactly the issued sequence: no duplicates, no collisions, replay order preserved.
        assertThat(durableIds).containsExactlyElementsOf(issued);
    }

    @Test
    void writeThroughGivesUpOnlyAfterRepeatedIdCollisions() {
        gateway.failAppends = true;
        mapper.duplicateIds.add(101L);
        mapper.duplicateIds.add(102L);
        mapper.duplicateIds.add(103L);

        assertThatThrownBy(() -> store.append(RUN, "ai_delta", event("ai_delta", "x")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private StudentExplanationStreamEvent event(String type, String message) {
        return new StudentExplanationStreamEvent(type, message, null, null, null, null, null, null, List.of());
    }

    private String json(StudentExplanationStreamEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static StudentExplanationWorkflowEventEntity row(long id, String runId, String name, String eventJson) {
        StudentExplanationWorkflowEventEntity entity = new StudentExplanationWorkflowEventEntity();
        entity.setEventId(id);
        entity.setRunId(runId);
        entity.setEventName(name);
        entity.setEventJson(eventJson);
        return entity;
    }

    /** In-memory double for the Lua gateway: honors seed re-basing, per-run cap and scripted Redis failures. */
    private static final class FakeGateway implements StudentExplanationWorkflowEventRedisGateway {
        private final Map<String, List<BufferedEvent>> lists = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong(0L);
        private boolean failAppends;
        private boolean failReads;
        private int capacity = Integer.MAX_VALUE;

        void buffer(String runId, long id, String name, String eventJson) {
            lists.computeIfAbsent(runId, key -> new ArrayList<>()).add(new BufferedEvent(id, name, eventJson));
        }

        int size(String runId) {
            return lists.getOrDefault(runId, List.of()).size();
        }

        @Override
        public long append(String runId, String eventName, String eventJson, long seed) {
            if (failAppends) {
                throw new QueryTimeoutException("simulated Redis unavailable");
            }
            List<BufferedEvent> list = lists.computeIfAbsent(runId, key -> new ArrayList<>());
            if (list.size() >= capacity) {
                return BUFFER_FULL;
            }
            // Mirrors the Lua: the sequence may never go below the caller-provided high-water seed.
            long id = Math.max(sequence.get(), seed) + 1L;
            sequence.set(id);
            list.add(new BufferedEvent(id, eventName, eventJson));
            return id;
        }

        @Override
        public List<BufferedEvent> readAfter(String runId, long afterEventId, int limit) {
            if (failReads) {
                throw new QueryTimeoutException("simulated Redis unavailable");
            }
            return lists.getOrDefault(runId, List.of()).stream()
                    .filter(item -> item.eventId() > afterEventId)
                    .limit(Math.max(1, limit))
                    .toList();
        }

        @Override
        public List<BufferedEvent> peekOldest(String runId, int limit) {
            List<BufferedEvent> list = lists.getOrDefault(runId, List.of());
            return List.copyOf(list.subList(0, Math.min(list.size(), Math.max(1, limit))));
        }

        @Override
        public long confirmFlushed(String runId, int flushedCount) {
            List<BufferedEvent> list = lists.get(runId);
            if (list == null) {
                return 0L;
            }
            // Mirrors Redis LTRIM: drop exactly the flushed head slice.
            for (int index = 0; index < flushedCount && !list.isEmpty(); index += 1) {
                list.remove(0);
            }
            if (list.isEmpty()) {
                lists.remove(runId);
            }
            return list.size();
        }

        @Override
        public Set<String> activeRuns() {
            return new HashSet<>(lists.keySet());
        }
    }

    /** Proxy mapper double with AUTO-collision semantics: scripted failures, replayable duplicates, id lookups. */
    private static final class FakeMapper {
        final Map<Long, StudentExplanationWorkflowEventEntity> rows = new LinkedHashMap<>();
        final Set<Long> duplicateIds = new HashSet<>();
        RuntimeException failInsert;
        long maxEventId = 100L;

        StudentExplanationWorkflowEventMapper proxy() {
            return (StudentExplanationWorkflowEventMapper) Proxy.newProxyInstance(
                    StudentExplanationWorkflowEventMapper.class.getClassLoader(),
                    new Class<?>[] {StudentExplanationWorkflowEventMapper.class},
                    (instance, method, args) -> switch (method.getName()) {
                        case "toString" -> "fakeEventMapper";
                        case "selectMaxEventId" -> maxEventId;
                        case "insertWithExplicitId" -> insert((StudentExplanationWorkflowEventEntity) args[0]);
                        case "selectById" -> rows.get(toLong((Serializable) args[0]));
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private int insert(StudentExplanationWorkflowEventEntity entity) {
            if (failInsert != null) {
                throw failInsert;
            }
            if (duplicateIds.contains(entity.getEventId()) || rows.containsKey(entity.getEventId())) {
                throw new DuplicateKeyException("simulated uk on event_id " + entity.getEventId());
            }
            rows.put(entity.getEventId(), entity);
            return 1;
        }

        /** Transaction simulation hook: rollback must undo half-inserted batches exactly like a real rollback. */
        Map<Long, StudentExplanationWorkflowEventEntity> snapshot() {
            return new LinkedHashMap<>(rows);
        }

        void restore(Map<Long, StudentExplanationWorkflowEventEntity> snapshot) {
            rows.keySet().retainAll(snapshot.keySet());
            rows.putAll(snapshot);
        }

        private static Long toLong(Serializable value) {
            return value instanceof Number number ? number.longValue() : null;
        }

        private static Object defaultValue(Class<?> type) {
            if (type == int.class) {
                return 0;
            }
            if (type == boolean.class) {
                return false;
            }
            return null;
        }
    }

    /** Executes callbacks inline but snapshots/restores the mapper rows so per-batch atomicity is real in tests. */
    private static final class FakeTransaction implements TransactionOperations {
        private final FakeMapper mapper;
        int rounds;

        private FakeTransaction(FakeMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            rounds += 1;
            Map<Long, StudentExplanationWorkflowEventEntity> snapshot = mapper.snapshot();
            try {
                return action.doInTransaction(null);
            } catch (RuntimeException exception) {
                mapper.restore(snapshot);
                throw exception;
            }
        }
    }

    /** Records the delegating calls the decorator must pass straight through and serves scripted flushed pages. */
    private static final class FakeMyBatisStore extends MyBatisStudentExplanationWorkflowStore {
        List<WorkflowEvent> flushed = List.of();
        int createOrLoads;
        int completes;
        int fails;

        private FakeMyBatisStore() {
            super(null, null, new ObjectMapper());
        }

        @Override
        public WorkflowRun createOrLoad(RequestSubject subject, StudentExplanationRequest request) {
            createOrLoads += 1;
            return new WorkflowRun(RUN, "fp", "RUNNING", null, null, null, true);
        }

        @Override
        public WorkflowEvent append(String runId, String eventName, StudentExplanationStreamEvent event) {
            throw new AssertionError("the decorator must not use the auto-increment delegate append");
        }

        @Override
        public List<WorkflowEvent> eventsAfter(String runId, long afterEventId, int limit) {
            return flushed;
        }

        @Override
        public void complete(String runId, StudentExplanationResponse response) {
            completes += 1;
        }

        @Override
        public void fail(String runId, String errorCode, String errorMessage) {
            fails += 1;
        }
    }
}
