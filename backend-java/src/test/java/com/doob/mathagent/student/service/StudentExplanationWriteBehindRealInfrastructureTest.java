package com.doob.mathagent.student.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.entity.StudentExplanationWorkflowEventEntity;
import com.doob.mathagent.student.mapper.StudentExplanationWorkflowEventMapper;
import com.doob.mathagent.student.service.StudentExplanationWorkflowEventRedisGateway.BufferedEvent;
import com.doob.mathagent.student.vo.StudentExplanationStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Real-infrastructure probe for the write-behind buffer. The Lua append/confirm scripts, key TTLs, the entry wire
 * format and the sequence reseed after a Redis key loss can only be proven against an actual Redis; the explicit-id
 * insert semantics on an AUTO_INCREMENT primary key can only be proven against an actual MySQL (the flush-path SQL
 * text is sent verbatim here, so a schema/driver surprise fails fast instead of degrading live traffic).
 *
 * <p>Gating mirrors {@code SpringContextStartupSmokeTest}: skipped unless {@code -Dmathagent.writebehind=true} and
 * {@code REDIS_PASSWORD} are present, so the default suite stays offline. Endpoints default to the Compose NAT
 * mappings (Redis 6380, MySQL 3307) because container service names do not resolve from the Windows host. Redis keys
 * live under a unique per-run prefix that is deleted afterwards; MySQL rows are removed via their own run row.</p>
 */
@EnabledIfSystemProperty(named = "mathagent.writebehind", matches = "true")
@EnabledIfEnvironmentVariable(named = "REDIS_PASSWORD", matches = ".+")
class StudentExplanationWriteBehindRealInfrastructureTest {

    private static final String REDIS_HOST = System.getProperty("mathagent.writebehind.redis.host", "127.0.0.1");
    private static final int REDIS_PORT = Integer.getInteger("mathagent.writebehind.redis.port", 6380);
    private static final String MYSQL_URL = System.getProperty("mathagent.writebehind.mysql.url",
            "jdbc:mysql://127.0.0.1:3307/math_agent_rag?useUnicode=true&characterEncoding=utf8&useSSL=false"
                    + "&allowPublicKeyRetrieval=true");

    @Test
    void bufferedAppendsFlushThroughRealLuaAndReseedAfterSequenceLoss() {
        RedisFixture redis = new RedisFixture();
        try {
            String runId = UUID.randomUUID().toString();
            long first = redis.store.append(runId, "progress", redis.event("p1")).eventId();
            long second = redis.store.append(runId, "ai_delta", redis.event("A")).eventId();
            assertThat(second).isGreaterThan(first);
            assertThat(redis.template.opsForList().size(redis.listKey(runId))).isEqualTo(2L);
            // Replay already sees the unflushed events because reads merge the real Redis buffer.
            assertThat(redis.store.eventsAfter(runId, 0L, 100))
                    .extracting(StudentExplanationWorkflowStore.WorkflowEvent::eventId)
                    .containsExactly(first, second);

            redis.store.flushPendingEvents();

            assertThat(redis.rows.values()).containsExactly(first, second);
            // The confirm script trimmed the list and pruned the run from the active index.
            assertThat(redis.template.hasKey(redis.listKey(runId))).isFalse();
            assertThat(redis.template.opsForSet().isMember(redis.runsKey(), runId)).isFalse();
            assertThat(redis.store.eventsAfter(runId, 0L, 100)).isEmpty();

            long third = redis.store.append(runId, "ai_delta", redis.event("B")).eventId();
            // Simulate a Redis restart that lost the sequence key: the next append must reseed from the JVM
            // high-water instead of restarting at 1 (which would produce colliding, out-of-order cursor ids).
            redis.template.delete(redis.seqKey());
            long fourth = redis.store.append(runId, "ai_delta", redis.event("C")).eventId();
            assertThat(fourth).isGreaterThan(third);

            long terminal = redis.store.append(runId, "completed", redis.event("done")).eventId();
            assertThat(terminal).isGreaterThan(fourth);
            redis.store.flushPendingEvents();

            List<Long> durable = new ArrayList<>(redis.rows.values());
            assertThat(durable).doesNotHaveDuplicates();
            assertThat(durable).isSorted();
            assertThat(durable).containsExactly(first, second, third, fourth, terminal);
        } finally {
            redis.close();
        }
    }

    @Test
    void bufferedEntrySurvivesRoundTripThroughRealRedisAndPreservesChineseText() throws Exception {
        RedisFixture redis = new RedisFixture();
        try {
            String runId = UUID.randomUUID().toString();
            StudentExplanationStreamEvent original = redis.event("解释：二次函数 y=x² \"quoted\" 换行\n结束");
            long id = redis.store.append(runId, "ai_delta", original).eventId();

            List<BufferedEvent> peeked = redis.gateway.peekOldest(runId, 10);

            assertThat(peeked).hasSize(1);
            assertThat(peeked.get(0).eventId()).isEqualTo(id);
            assertThat(peeked.get(0).eventName()).isEqualTo("ai_delta");
            StudentExplanationStreamEvent reparsed = redis.mapper
                    .readValue(peeked.get(0).eventJson(), StudentExplanationStreamEvent.class);
            assertThat(reparsed.message()).isEqualTo(original.message());
            // TTL refreshed by the append script must cover the restart window (configured 2h here).
            assertThat(redis.template.getExpire(redis.listKey(runId)))
                    .isGreaterThan(Duration.ofHours(2).minusMinutes(5).getSeconds());
        } finally {
            redis.close();
        }
    }

    /**
     * Proves the flush-path SQL against the deployed schema: an explicit event_id inserts fine on the AUTO_INCREMENT
     * column and pushes the generated counter above it, which is what keeps a later auto-assigned id from ever
     * colliding with future explicit ids.
     */
    @Test
    void explicitEventIdInsertWorksOnRealMysqlAutoIncrementColumn() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL_URL, "root", System.getenv("MYSQL_ROOT_PASSWORD"))) {
            long maxBefore;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COALESCE(MAX(event_id), 0) FROM student_explanation_workflow_event");
                 ResultSet result = query.executeQuery()) {
                result.next();
                maxBefore = result.getLong(1);
            }
            String runId = UUID.randomUUID().toString();
            try (PreparedStatement run = connection.prepareStatement(
                    "INSERT INTO student_explanation_workflow_run (run_id, tenant_id, subject_type, subject_id, "
                            + "client_request_id, request_fingerprint, request_json, status, retry_count) "
                            + "VALUES (?, 'default', 'student', 'it-probe', ?, ?, '{}', 'RUNNING', 0)")) {
                run.setString(1, runId);
                run.setString(2, "it-" + UUID.randomUUID());
                run.setString(3, "0".repeat(64));
                run.executeUpdate();
            }
            long explicitId = maxBefore + 1;
            try (PreparedStatement event = connection.prepareStatement(
                    "INSERT INTO student_explanation_workflow_event (event_id, run_id, event_name, event_json) "
                            + "VALUES (?, ?, 'progress', '{}')")) {
                event.setLong(1, explicitId);
                event.setString(2, runId);
                event.executeUpdate();
            }
            long autoId;
            try (PreparedStatement auto = connection.prepareStatement(
                    "INSERT INTO student_explanation_workflow_event (run_id, event_name, event_json) "
                            + "VALUES (?, 'ai_delta', '{}')", Statement.RETURN_GENERATED_KEYS)) {
                auto.setString(1, runId);
                auto.executeUpdate();
                try (ResultSet keys = auto.getGeneratedKeys()) {
                    keys.next();
                    autoId = keys.getLong(1);
                }
            }
            assertThat(autoId).isGreaterThan(explicitId);
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM student_explanation_workflow_run WHERE run_id = ?")) {
                cleanup.setString(1, runId);
                cleanup.executeUpdate(); // FK cascade removes both event rows
            }
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT COUNT(*) FROM student_explanation_workflow_event WHERE run_id = ?")) {
                check.setString(1, runId);
                try (ResultSet result = check.executeQuery()) {
                    result.next();
                    assertThat(result.getLong(1)).isZero();
                }
            }
        }
    }

    /** Real Redis template + real gateway + store over in-memory MySQL fakes; every key sits under a unique prefix. */
    private static final class RedisFixture {
        private final String prefix;
        private final LettuceConnectionFactory factory;
        private final StringRedisTemplate template;
        private final StringRedisStudentExplanationWorkflowEventGateway gateway;
        private final Map<Long, Long> rows = new LinkedHashMap<>();
        private final RedisWriteBehindStudentExplanationWorkflowStore store;
        private final ObjectMapper mapper = new ObjectMapper();

        private RedisFixture() {
            prefix = "math-agent:test:wb-" + UUID.randomUUID();
            factory = new LettuceConnectionFactory(REDIS_HOST, REDIS_PORT);
            String redisPassword = System.getenv("REDIS_PASSWORD");
            if (redisPassword != null && !redisPassword.isBlank()) {
                factory.setPassword(redisPassword);
            }
            factory.afterPropertiesSet();
            template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            StudentExplanationWriteBehindProperties properties = new StudentExplanationWriteBehindProperties(
                    true, prefix, 200, Duration.ofHours(2), 5000);
            gateway = new StringRedisStudentExplanationWorkflowEventGateway(template, properties);
            store = new RedisWriteBehindStudentExplanationWorkflowStore(
                    new EmptyResultDelegate(), gateway, recordingMapper(), inlineTransactions(), mapper, properties);
            store.initialize();
        }

        /** Only the probe's own bookkeeping; the decorator's SQL is exercised by the MySQL test against the schema. */
        private StudentExplanationWorkflowEventMapper recordingMapper() {
            return (StudentExplanationWorkflowEventMapper) Proxy.newProxyInstance(
                    StudentExplanationWorkflowEventMapper.class.getClassLoader(),
                    new Class<?>[] {StudentExplanationWorkflowEventMapper.class},
                    (instance, method, args) -> switch (method.getName()) {
                        case "toString" -> "fakeEventMapper";
                        case "selectMaxEventId" -> rows.keySet().stream().mapToLong(Long::longValue).max().orElse(100L);
                        case "insertWithExplicitId" -> insert((StudentExplanationWorkflowEventEntity) args[0]);
                        default -> null;
                    });
        }

        private int insert(StudentExplanationWorkflowEventEntity entity) {
            rows.put(entity.getEventId(), entity.getEventId());
            return 1;
        }

        private String seqKey() {
            return prefix + ":seq";
        }

        private String listKey(String runId) {
            return prefix + ":run:" + runId + ":events";
        }

        private String runsKey() {
            return prefix + ":runs";
        }

        private StudentExplanationStreamEvent event(String message) {
            return new StudentExplanationStreamEvent("ai_delta", message, null, null, null, null, null, null, List.of());
        }

        private void close() {
            template.keys(prefix + "*").forEach(template::delete);
            factory.destroy();
        }
    }

    /** The delegate fake must answer replay reads with "nothing flushed", so merged results prove the buffer alone. */
    private static final class EmptyResultDelegate extends MyBatisStudentExplanationWorkflowStore {
        private EmptyResultDelegate() {
            super(null, null, new ObjectMapper());
        }

        @Override
        public List<WorkflowEvent> eventsAfter(String runId, long afterEventId, int limit) {
            return List.of();
        }
    }

    private static TransactionOperations inlineTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }
}
