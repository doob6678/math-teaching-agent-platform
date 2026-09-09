package com.doob.mathagent.agent.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Dispatches durable task events and repairs only bounded, version-specific recovery gaps. */
@Component
@ConditionalOnProperty(name = "math-agent.rabbitmq.listeners-enabled", havingValue = "true")
public class AgentWorkerTaskOutboxScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkerTaskOutboxScheduler.class);
    private final AgentWorkerTaskDispatchService dispatchService;
    private final AgentWorkerTaskOutboxStore outboxStore;
    private final AgentWorkerTaskOutboxPublisher publisher;
    private final Counter orphanRepaired;
    private final Counter publishingLeaseRecovered;
    private final int reconciliationLimit;
    private final Duration reconciliationGrace;
    // Backlog alert without an external monitoring stack: the publisher's oldest-pending gauge is only observable
    // when /actuator/metrics is scraped, and this deployment scrapes nothing, so a task stuck READY would go
    // unnoticed forever. The scheduler already runs every second, so it doubles as the watchdog: WARN once the
    // oldest pending row ages past the threshold, rate-limited by the cooldown to keep logs readable.
    private final long backlogWarnSeconds;
    private final Duration backlogWarnCooldown;
    private Instant lastBacklogWarnAt = Instant.EPOCH;
    private final TaskScheduler taskScheduler;
    // Coalesces wake events: while one immediate round is already queued, further enqueues are covered by it.
    private final AtomicBoolean wakeQueued = new AtomicBoolean();

    public AgentWorkerTaskOutboxScheduler(
            AgentWorkerTaskDispatchService dispatchService,
            AgentWorkerTaskOutboxStore outboxStore,
            AgentWorkerTaskPublisher taskPublisher,
            Environment environment,
            MeterRegistry meterRegistry,
            TaskScheduler taskScheduler) {
        this.dispatchService = dispatchService;
        this.outboxStore = outboxStore;
        this.taskScheduler = taskScheduler;
        this.backlogWarnSeconds = Math.max(1L, environment.getProperty("math-agent.agent-worker.outbox.backlog-warn-seconds", Long.class, 120L));
        this.backlogWarnCooldown = Duration.ofSeconds(Math.max(5L,
                environment.getProperty("math-agent.agent-worker.outbox.backlog-warn-cooldown-seconds", Long.class, 60L)));
        // The batch claim read-back selects rows by locked_by, so each process must publish under its own
        // identity: a shared configured id would let two instances see each other's claimed rows and
        // double-publish. The base id keeps logs readable; the nonce makes the claimant unique per process.
        String publisherId = instancePublisherId(
                environment.getProperty("math-agent.agent-worker.outbox.publisher-id", "agent-worker-outbox"));
        int batchSize = environment.getProperty("math-agent.agent-worker.outbox.batch-size", Integer.class, 100);
        long leaseSeconds = environment.getProperty("math-agent.agent-worker.outbox.publish-lease-seconds", Long.class, 30L);
        this.publisher = new AgentWorkerTaskOutboxPublisher(
                outboxStore, taskPublisher, publisherId, batchSize, Duration.ofSeconds(Math.max(5L, leaseSeconds)), meterRegistry);
        this.reconciliationLimit = Math.max(1, environment.getProperty("math-agent.agent-worker.outbox.reconciliation-limit", Integer.class, 100));
        long graceSeconds = environment.getProperty("math-agent.agent-worker.outbox.reconciliation-grace-seconds", Long.class, 30L);
        this.reconciliationGrace = Duration.ofSeconds(Math.max(1L, graceSeconds));
        this.orphanRepaired = Counter.builder("agent_worker_outbox_orphan_queued_repaired_total").register(meterRegistry);
        this.publishingLeaseRecovered = Counter.builder("agent_worker_outbox_publishing_timeout_recovered_total").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${math-agent.agent-worker.outbox.fixed-delay-ms:1000}")
    public void publishPendingEvents() {
        publishRound();
    }

    /**
     * Wake-on-commit fast path: right after a transaction that enqueued an outbox row commits, run the same
     * delivery round immediately instead of waiting up to one poll interval. The wake is dispatched onto the
     * scheduling executor and coalesced, so bursts of submissions queue at most one extra round; the 1s poll
     * stays untouched as the durability fallback (a lost or duplicate wake changes nothing observable).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOutboxWake(AgentWorkerOutboxWakeEvent wake) {
        if (wakeQueued.compareAndSet(false, true)) {
            // One-shot immediate schedule on the shared scheduling executor: with the default single-threaded
            // pool this queues right behind any running poll round, keeping rounds serialized for free.
            taskScheduler.schedule(() -> {
                wakeQueued.set(false);
                publishRound();
            }, Instant.now());
        }
    }

    // synchronized guards overlap between the scheduled thread and the wake task even if the scheduling pool
    // is enlarged later; two rounds racing the same batch claim would otherwise double-process in-process.
    private synchronized void publishRound() {
        int recovered = dispatchService.recoverExpiredPublishing(Instant.now());
        if (recovered > 0) {
            publishingLeaseRecovered.increment(recovered);
        }
        int repaired = dispatchService.reconcileOrphanQueued(Instant.now().minus(reconciliationGrace), reconciliationLimit);
        if (repaired > 0) {
            orphanRepaired.increment(repaired);
        }
        publisher.publishPendingEvents();
        warnOnBacklog(Instant.now());
    }

    /** Configured publisher id plus a per-process nonce; see usage site for why claims must be process-unique. */
    static String instancePublisherId(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void warnOnBacklog(Instant now) {
        Instant oldest = outboxStore.oldestPendingCreatedAt();
        if (oldest == null) {
            return;
        }
        long ageSeconds = Duration.between(oldest, now).getSeconds();
        if (ageSeconds >= backlogWarnSeconds && Duration.between(lastBacklogWarnAt, now).compareTo(backlogWarnCooldown) >= 0) {
            lastBacklogWarnAt = now;
            log.warn("agent_worker_outbox_backlog_alert oldestPendingAgeSeconds={} thresholdSeconds={} pendingCount={} "
                            + "hint=\"broker likely unavailable; rows retry with capped backoff, inspect connectivity and the console traces\"",
                    ageSeconds, backlogWarnSeconds, outboxStore.pendingCount());
        }
    }
}
