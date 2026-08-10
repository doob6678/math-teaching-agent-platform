package com.doob.mathagent.teaching.mq;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reissues expired lecture work through the durable outbox after a worker restart. */
@Component
@ConditionalOnProperty(name = "math-agent.rabbitmq.listeners-enabled", havingValue = "true")
public class LectureTaskLeaseRecovery {
    private final LectureTaskLeaseStore leaseStore;
    private final LectureTaskOutboxStore outboxStore;

    public LectureTaskLeaseRecovery(LectureTaskLeaseStore leaseStore, LectureTaskOutboxStore outboxStore) {
        this.leaseStore = leaseStore;
        this.outboxStore = outboxStore;
    }

    @Scheduled(fixedDelayString = "${math-agent.teaching.lecture-task.recovery-milliseconds:30000}")
    public void recover() {
        recoverExpired(Instant.now(), 100);
    }

    /** Reclaims a bounded set of expired leases and durably queues one replacement event for each. */
    @Transactional
    public int recoverExpired(Instant now, int limit) {
        java.util.List<String> reclaimed = leaseStore.reclaimExpired(now, limit);
        reclaimed.forEach(taskId -> outboxStore.enqueue(taskId, "LEASE_RECOVERY"));
        return reclaimed.size();
    }
}
