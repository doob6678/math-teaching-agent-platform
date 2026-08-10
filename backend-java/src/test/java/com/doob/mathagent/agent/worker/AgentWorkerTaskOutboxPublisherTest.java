package com.doob.mathagent.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

/** Contract tests for versioned Agent Worker outbox delivery and bounded recovery. */
class AgentWorkerTaskOutboxPublisherTest {

    @Test
    void brokerFailureReturnsClaimedEventToPendingWithBackoff() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        AgentWorkerTask task = task("task-1", 1);
        store.enqueue(task);
        AgentWorkerTaskOutboxPublisher publisher = new AgentWorkerTaskOutboxPublisher(
                store, failingPublisher(), "publisher-a", 10, Duration.ofSeconds(30), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        publisher.publishPendingEvents();

        assertThat(store.status("task-1", 1)).isEqualTo("PENDING");
        assertThat(store.error("task-1", 1)).contains("RabbitMQ unavailable");
        assertThat(store.attempts("task-1", 1)).isEqualTo(1);
    }

    @Test
    void onlyOnePublisherClaimsOneReadyEventAcrossConcurrentScans() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.enqueue(task("task-2", 1));
        Instant now = Instant.now();

        List<AgentWorkerTaskOutboxEvent> first = store.claimReady("publisher-a", now, Duration.ofSeconds(30), 10);
        List<AgentWorkerTaskOutboxEvent> second = store.claimReady("publisher-b", now, Duration.ofSeconds(30), 10);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(store.status("task-2", 1)).isEqualTo("PUBLISHING");
    }

    @Test
    void expiredPublishingLeaseIsMadePendingForAtLeastOnceRecovery() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.enqueue(task("task-3", 1));
        Instant now = Instant.now();
        store.claimReady("publisher-a", now, Duration.ofSeconds(1), 10);

        assertThat(store.recoverExpiredPublishing(now.plusSeconds(2))).isEqualTo(1);
        assertThat(store.status("task-3", 1)).isEqualTo("PENDING");
    }

    @Test
    void reconciliationCreatesOnlyMissingCurrentDispatchVersion() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        AgentWorkerTask task = task("task-4", 2);
        store.addQueued(task, Instant.now().minusSeconds(31));
        store.enqueue(task);

        assertThat(store.findOrphanQueued(Instant.now().minusSeconds(30), 100)).isEmpty();

        store.remove("task-4", 2);
        assertThat(store.findOrphanQueued(Instant.now().minusSeconds(30), 100))
                .extracting(AgentWorkerTask::taskId, AgentWorkerTask::dispatchVersion)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("task-4", 2));
        store.enqueue(task);
        assertThat(store.events()).hasSize(1);
    }

    private static AgentWorkerTaskPublisher failingPublisher() {
        return new AgentWorkerTaskPublisher(null) {
            @Override public void publish(AgentWorkerTaskOutboxEvent event) {
                throw new AmqpException("RabbitMQ unavailable");
            }
        };
    }

    private static AgentWorkerTask task(String taskId, int dispatchVersion) {
        Instant now = Instant.now();
        return new AgentWorkerTask(taskId, "workflow-1", "tenant-a", "PythonHandoutAgent", "python_handout",
                "QUEUED", 0, dispatchVersion, null, null, null, "{}", null, now, now);
    }

    private static final class InMemoryOutboxStore implements AgentWorkerTaskOutboxStore {
        private final Map<String, AgentWorkerTaskOutboxEvent> events = new HashMap<>();
        private final Map<String, Instant> queued = new HashMap<>();

        @Override public void enqueue(AgentWorkerTask task) {
            String key = key(task.taskId(), task.dispatchVersion());
            events.putIfAbsent(key, new AgentWorkerTaskOutboxEvent("event-" + key, task.taskId(), task.dispatchVersion(),
                    task.agentCode(), task.stageCode(), "PENDING", 0, Instant.now(), null, null, null, Instant.now()));
        }

        @Override public List<AgentWorkerTaskOutboxEvent> claimReady(String publisherId, Instant now, Duration leaseDuration, int limit) {
            List<AgentWorkerTaskOutboxEvent> result = new ArrayList<>();
            for (Map.Entry<String, AgentWorkerTaskOutboxEvent> entry : events.entrySet()) {
                AgentWorkerTaskOutboxEvent event = entry.getValue();
                if (result.size() >= limit || !"PENDING".equals(event.status()) || event.nextAttemptAt().isAfter(now)) continue;
                AgentWorkerTaskOutboxEvent claimed = new AgentWorkerTaskOutboxEvent(event.eventId(), event.taskId(), event.dispatchVersion(),
                        event.agentCode(), event.stageCode(), "PUBLISHING", event.publishAttempt() + 1, event.nextAttemptAt(),
                        now.plus(leaseDuration), publisherId, event.lastError(), event.createdAt());
                events.put(entry.getKey(), claimed);
                result.add(claimed);
            }
            return List.copyOf(result);
        }

        @Override public boolean markPublished(AgentWorkerTaskOutboxEvent event, Instant publishedAt) {
            AgentWorkerTaskOutboxEvent current = events.get(key(event.taskId(), event.dispatchVersion()));
            if (current == null || !"PUBLISHING".equals(current.status()) || !event.lockedBy().equals(current.lockedBy())) return false;
            events.put(key(event.taskId(), event.dispatchVersion()), new AgentWorkerTaskOutboxEvent(current.eventId(), current.taskId(), current.dispatchVersion(),
                    current.agentCode(), current.stageCode(), "PUBLISHED", current.publishAttempt(), current.nextAttemptAt(), null, null, null, current.createdAt()));
            return true;
        }

        @Override public void releaseForRetry(AgentWorkerTaskOutboxEvent event, Instant nextAttemptAt, String errorSummary) {
            AgentWorkerTaskOutboxEvent current = events.get(key(event.taskId(), event.dispatchVersion()));
            if (current != null && "PUBLISHING".equals(current.status()) && event.lockedBy().equals(current.lockedBy())) {
                events.put(key(event.taskId(), event.dispatchVersion()), new AgentWorkerTaskOutboxEvent(current.eventId(), current.taskId(), current.dispatchVersion(),
                        current.agentCode(), current.stageCode(), "PENDING", current.publishAttempt(), nextAttemptAt, null, null, errorSummary, current.createdAt()));
            }
        }

        @Override public int recoverExpiredPublishing(Instant now) {
            int recovered = 0;
            for (Map.Entry<String, AgentWorkerTaskOutboxEvent> entry : events.entrySet()) {
                AgentWorkerTaskOutboxEvent current = entry.getValue();
                if ("PUBLISHING".equals(current.status()) && current.publishLeaseUntil().isBefore(now)) {
                    events.put(entry.getKey(), new AgentWorkerTaskOutboxEvent(current.eventId(), current.taskId(), current.dispatchVersion(),
                            current.agentCode(), current.stageCode(), "PENDING", current.publishAttempt(), now, null, null,
                            "publisher lease expired", current.createdAt()));
                    recovered++;
                }
            }
            return recovered;
        }

        @Override public List<AgentWorkerTask> findOrphanQueued(Instant olderThan, int limit) {
            return queued.entrySet().stream()
                    .filter(entry -> entry.getValue().isBefore(olderThan))
                    .map(entry -> entry.getKey().split(":", 2))
                    .map(parts -> task(parts[0], Integer.parseInt(parts[1])))
                    .filter(task -> !events.containsKey(key(task.taskId(), task.dispatchVersion())))
                    .limit(limit).toList();
        }

        @Override public long pendingCount() { return events.values().stream().filter(event -> !"PUBLISHED".equals(event.status())).count(); }
        @Override public Instant oldestPendingCreatedAt() { return events.values().stream().filter(event -> !"PUBLISHED".equals(event.status())).map(AgentWorkerTaskOutboxEvent::createdAt).min(Instant::compareTo).orElse(null); }
        void addQueued(AgentWorkerTask task, Instant updatedAt) { queued.put(key(task.taskId(), task.dispatchVersion()), updatedAt); }
        void remove(String taskId, int dispatchVersion) { events.remove(key(taskId, dispatchVersion)); }
        String status(String taskId, int dispatchVersion) { return events.get(key(taskId, dispatchVersion)).status(); }
        String error(String taskId, int dispatchVersion) { return events.get(key(taskId, dispatchVersion)).lastError(); }
        int attempts(String taskId, int dispatchVersion) { return events.get(key(taskId, dispatchVersion)).publishAttempt(); }
        List<AgentWorkerTaskOutboxEvent> events() { return List.copyOf(events.values()); }
        private static String key(String taskId, int dispatchVersion) { return taskId + ":" + dispatchVersion; }
    }
}
