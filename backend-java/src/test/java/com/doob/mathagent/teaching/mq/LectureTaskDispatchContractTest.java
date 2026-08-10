package com.doob.mathagent.teaching.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the lecture-task control plane.
 *
 * <p>These tests deliberately use the real state-machine classes with in-memory persistence adapters. They prove
 * the broker contract is an opaque task id and that duplicate delivery cannot cause a second workflow execution.</p>
 */
class LectureTaskDispatchContractTest {

    @Test
    void persistsPendingOutboxEventAndPublishesOnlyTaskId() {
        InMemoryLectureTaskOutboxStore store = new InMemoryLectureTaskOutboxStore();
        List<String> deliveredTaskIds = new ArrayList<>();
        LectureTaskOutboxPublisher publisher = new LectureTaskOutboxPublisher(
                store, deliveredTaskIds::add, 100);

        store.enqueue("lecture-task-1");
        publisher.publishPendingEvents();

        assertThat(deliveredTaskIds).containsExactly("lecture-task-1");
        assertThat(store.pending()).isEmpty();
        assertThat(store.published()).extracting(LectureTaskOutboxEvent::taskId).containsExactly("lecture-task-1");
    }

    @Test
    void brokerFailureLeavesOutboxPendingForLaterCompensation() {
        InMemoryLectureTaskOutboxStore store = new InMemoryLectureTaskOutboxStore();
        store.enqueue("lecture-task-2");
        LectureTaskOutboxPublisher publisher = new LectureTaskOutboxPublisher(
                store, taskId -> { throw new IllegalStateException("RabbitMQ unavailable"); }, 100);

        publisher.publishPendingEvents();

        assertThat(store.pending()).extracting(LectureTaskOutboxEvent::taskId).containsExactly("lecture-task-2");
    }

    @Test
    void duplicateDeliveryAllowsOnlyOneWorkerToOwnLease() {
        InMemoryLectureTaskLeaseStore store = new InMemoryLectureTaskLeaseStore();
        store.create("lecture-task-3");

        LectureTaskLease first = store.tryAcquire("lecture-task-3", "worker-a", Instant.now(), Duration.ofMinutes(5));
        LectureTaskLease duplicate = store.tryAcquire("lecture-task-3", "worker-b", Instant.now(), Duration.ofMinutes(5));

        assertThat(first).isNotNull();
        assertThat(duplicate).isNull();
    }

    @Test
    void expiredLeaseCanBeTakenOverByAnotherWorker() {
        InMemoryLectureTaskLeaseStore store = new InMemoryLectureTaskLeaseStore();
        store.create("lecture-task-4");
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        assertThat(store.tryAcquire("lecture-task-4", "worker-a", now, Duration.ofSeconds(1))).isNotNull();

        LectureTaskLease takeover = store.tryAcquire("lecture-task-4", "worker-b", now.plusSeconds(2), Duration.ofMinutes(5));

        assertThat(takeover).isNotNull();
        assertThat(takeover.workerId()).isEqualTo("worker-b");
    }

    @Test
    void expiredLeaseIsRequeuedExactlyOnceThroughTheOutbox() {
        InMemoryLectureTaskLeaseStore leaseStore = new InMemoryLectureTaskLeaseStore();
        InMemoryLectureTaskOutboxStore outboxStore = new InMemoryLectureTaskOutboxStore();
        LectureTaskLeaseRecovery recovery = new LectureTaskLeaseRecovery(leaseStore, outboxStore);
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        leaseStore.create("lecture-task-recovery");
        assertThat(leaseStore.tryAcquire("lecture-task-recovery", "worker-a", now, Duration.ofSeconds(1))).isNotNull();

        assertThat(recovery.recoverExpired(now.plusSeconds(2), 100)).isEqualTo(1);
        assertThat(recovery.recoverExpired(now.plusSeconds(2), 100)).isZero();
        assertThat(leaseStore.status("lecture-task-recovery")).isEqualTo(LectureTaskLeaseStatus.RETRYING);
        assertThat(outboxStore.pending()).extracting(LectureTaskOutboxEvent::taskId)
                .containsExactly("lecture-task-recovery");
    }

    @Test
    void activeLeaseIsNotRequeuedByRecovery() {
        InMemoryLectureTaskLeaseStore leaseStore = new InMemoryLectureTaskLeaseStore();
        InMemoryLectureTaskOutboxStore outboxStore = new InMemoryLectureTaskOutboxStore();
        LectureTaskLeaseRecovery recovery = new LectureTaskLeaseRecovery(leaseStore, outboxStore);
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        leaseStore.create("lecture-task-active");
        assertThat(leaseStore.tryAcquire("lecture-task-active", "worker-a", now, Duration.ofMinutes(5))).isNotNull();

        assertThat(recovery.recoverExpired(now.plusSeconds(2), 100)).isZero();
        assertThat(leaseStore.status("lecture-task-active")).isEqualTo(LectureTaskLeaseStatus.RUNNING);
        assertThat(outboxStore.pending()).isEmpty();
    }

    @Test
    void thirdFailureBecomesTerminalAndRequestsDeadLettering() {
        InMemoryLectureTaskLeaseStore store = new InMemoryLectureTaskLeaseStore();
        store.create("lecture-task-5");
        Instant now = Instant.now();
        LectureTaskLease first = store.tryAcquire("lecture-task-5", "worker", now, Duration.ofMinutes(5));
        assertThat(store.failOrRetry(first, "first", 3)).isTrue();
        LectureTaskLease second = store.tryAcquire("lecture-task-5", "worker", now, Duration.ofMinutes(5));
        assertThat(store.failOrRetry(second, "second", 3)).isTrue();
        LectureTaskLease third = store.tryAcquire("lecture-task-5", "worker", now, Duration.ofMinutes(5));

        assertThat(store.failOrRetry(third, "third", 3)).isFalse();
        assertThat(store.status("lecture-task-5")).isEqualTo(LectureTaskLeaseStatus.FAILED);
        assertThat(store.lastError("lecture-task-5")).isEqualTo("third");
    }
}
