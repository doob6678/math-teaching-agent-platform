package com.doob.mathagent.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.entity.AgentWorkerTaskOutboxEventEntity;
import com.doob.mathagent.agent.mapper.AgentWorkerTaskMapper;
import com.doob.mathagent.agent.mapper.AgentWorkerTaskOutboxEventMapper;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

/**
 * Covers the batch-claim statement sequence (one UPDATE + one read-back, never per-row updates) and the
 * wake-on-commit signal emitted by the MySQL outbox store. Mapper fakes use dynamic proxies because the
 * project keeps the test classpath dependency-free (no Mockito); the scripted map carries both the return
 * values and, after running, the record of which mapper methods were touched.
 */
class MyBatisAgentWorkerTaskOutboxStoreTest {

    @Test
    void claimReadyUsesOneBatchUpdateAndReadsBackClaimedRows() {
        Map<String, Object> scripted = new HashMap<>();
        scripted.put("claimBatchPending", 2);
        scripted.put("selectClaimed", List.of(entity("e-1", "task-1"), entity("e-2", "task-2")));
        List<String> invoked = new ArrayList<>();

        List<AgentWorkerTaskOutboxEvent> claimed = store(scripted, invoked).claimReady(
                "pub-1", Instant.now(), Duration.ofSeconds(30), 100);

        assertThat(claimed).hasSize(2);
        assertThat(claimed).allSatisfy(event -> {
            assertThat(event.status()).isEqualTo("PUBLISHING");
            assertThat(event.lockedBy()).isEqualTo("pub-1");
        });
        // The batch statement replaced the per-row CAS loop entirely: BaseMapper.update must never run here.
        assertThat(invoked).doesNotContain("update");
    }

    @Test
    void claimReadySkipsReadBackWhenNothingWasClaimed() {
        Map<String, Object> scripted = new HashMap<>();
        scripted.put("claimBatchPending", 0);
        List<String> invoked = new ArrayList<>();

        assertThat(store(scripted, invoked).claimReady("pub-1", Instant.now(), Duration.ofSeconds(30), 100)).isEmpty();
        assertThat(invoked).doesNotContain("selectClaimed");
    }

    @Test
    void successfulEnqueuePublishesWakeForTheCommitPhaseListener() {
        RecordingEventPublisher events = new RecordingEventPublisher();
        MyBatisAgentWorkerTaskOutboxStore store = new MyBatisAgentWorkerTaskOutboxStore(
                mapper(new HashMap<>(), new ArrayList<>()), taskMapper(), events);

        store.enqueue(task("task-9"));

        assertThat(events.published).hasSize(1);
        assertThat(events.published.get(0)).isInstanceOf(AgentWorkerOutboxWakeEvent.class);
        assertThat(((AgentWorkerOutboxWakeEvent) events.published.get(0)).taskId()).isEqualTo("task-9");
    }

    @Test
    void duplicateEnqueueStaysSilentBecauseTheRowIsAlreadyPending() {
        Map<String, Object> scripted = new HashMap<>();
        scripted.put("insert", new DuplicateKeyException("uk_agent_worker_outbox_task_dispatch"));
        RecordingEventPublisher events = new RecordingEventPublisher();
        MyBatisAgentWorkerTaskOutboxStore store = new MyBatisAgentWorkerTaskOutboxStore(
                mapper(scripted, new ArrayList<>()), taskMapper(), events);

        store.enqueue(task("task-9"));

        assertThat(events.published).isEmpty();
    }

    /** Scripted answers win; unscripted calls get type-safe defaults (0/false/null) and are logged to {@code invoked}. */
    private static AgentWorkerTaskOutboxEventMapper mapper(Map<String, Object> scripted, List<String> invoked) {
        return (AgentWorkerTaskOutboxEventMapper) Proxy.newProxyInstance(
                AgentWorkerTaskOutboxEventMapper.class.getClassLoader(),
                new Class<?>[] {AgentWorkerTaskOutboxEventMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return "fakeOutboxMapper";
                    }
                    invoked.add(method.getName());
                    if (scripted.get(method.getName()) instanceof Throwable throwable) {
                        throw throwable;
                    }
                    if (scripted.containsKey(method.getName())) {
                        return scripted.get(method.getName());
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    private static AgentWorkerTaskMapper taskMapper() {
        return (AgentWorkerTaskMapper) Proxy.newProxyInstance(
                AgentWorkerTaskMapper.class.getClassLoader(),
                new Class<?>[] {AgentWorkerTaskMapper.class},
                (proxy, method, args) -> "toString".equals(method.getName()) ? "fakeTaskMapper" : null);
    }

    private static MyBatisAgentWorkerTaskOutboxStore store(Map<String, Object> scripted, List<String> invoked) {
        return new MyBatisAgentWorkerTaskOutboxStore(mapper(scripted, invoked), taskMapper(),
                new RecordingEventPublisher());
    }

    private static AgentWorkerTask task(String taskId) {
        Instant now = Instant.now();
        return new AgentWorkerTask(taskId, "workflow-1", "tenant-a", "PythonHandoutAgent",
                "python_handout", "QUEUED", 0, 1, null, null, null, "{}", null, now, now);
    }

    private static AgentWorkerTaskOutboxEventEntity entity(String eventId, String taskId) {
        Instant now = Instant.now();
        AgentWorkerTaskOutboxEventEntity entity = new AgentWorkerTaskOutboxEventEntity();
        entity.setEventId(eventId);
        entity.setTaskId(taskId);
        entity.setDispatchVersion(1);
        entity.setAgentCode("PythonHandoutAgent");
        entity.setStageCode("python_handout");
        entity.setStatus("PUBLISHING");
        entity.setPublishAttempt(1);
        entity.setNextAttemptAt(now);
        entity.setPublishLeaseUntil(now.plusSeconds(30));
        entity.setLockedBy("pub-1");
        entity.setCreatedAt(now);
        return entity;
    }

    private static final class RecordingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> published = new ArrayList<>();

        @Override public void publishEvent(ApplicationEvent event) { published.add(event); }
        @Override public void publishEvent(Object event) { published.add(event); }
    }
}
