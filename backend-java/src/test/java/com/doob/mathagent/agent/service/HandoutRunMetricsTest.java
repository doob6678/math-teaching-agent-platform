package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.mapper.HandoutRunMetricsMapper;
import com.doob.mathagent.agent.worker.AgentWorkerTask;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies Java queue/ACK measurements remain separate from Python provider usage accounting. */
class HandoutRunMetricsTest {

    @Test
    void recordsQueueWaitRetryAndAckWithoutChangingProviderFields() {
        AtomicReference<HandoutRunMetricsStore.MetricsRow> claim = new AtomicReference<>();
        AtomicReference<HandoutRunMetricsStore.MetricsRow> terminal = new AtomicReference<>();
        AtomicReference<HandoutRunMetricsStore.LifecycleRow> enqueued = new AtomicReference<>();
        AtomicReference<HandoutRunMetricsStore.LifecycleRow> publication = new AtomicReference<>();
        AtomicReference<HandoutRunMetricsStore.LifecycleRow> pdf = new AtomicReference<>();
        AtomicReference<HandoutRunMetricsStore.LifecycleRow> lease = new AtomicReference<>();
        AtomicReference<HandoutRunMetricsStore.LifecycleRow> dlq = new AtomicReference<>();
        HandoutRunMetricsMapper mapper = new HandoutRunMetricsMapper() {
            @Override public int recordEnqueued(HandoutRunMetricsStore.LifecycleRow row) { enqueued.set(row); return 1; }
            @Override public int recordClaim(HandoutRunMetricsStore.MetricsRow row) { claim.set(row); return 1; }
            @Override public int recordTerminal(HandoutRunMetricsStore.MetricsRow row) { terminal.set(row); return 1; }
            @Override public int recordLeaseWait(HandoutRunMetricsStore.LifecycleRow row) { lease.set(row); return 1; }
            @Override public int recordDeadLetter(HandoutRunMetricsStore.LifecycleRow row) { dlq.set(row); return 1; }
            @Override public int recordPublicationGate(HandoutRunMetricsStore.LifecycleRow row) { publication.set(row); return 1; }
            @Override public int recordPdf(HandoutRunMetricsStore.LifecycleRow row) { pdf.set(row); return 1; }
        };
        HandoutRunMetricsStore store = new HandoutRunMetricsStore(mapper);
        Instant created = Instant.parse("2026-08-06T00:00:00Z");
        Instant claimed = Instant.parse("2026-08-06T00:00:02Z");
        AgentWorkerTask task = new AgentWorkerTask(
                "task-1", "run-1", "tenant-a", "PythonHandoutAgent", "python_handout", "RUNNING", 2, 2,
                "lease", claimed.plusSeconds(900), "worker", "{}", null, created, claimed);

        store.recordEnqueued("run-1", "task-1", created);
        store.recordClaim(task, claimed);
        store.recordTerminal(task, "COMPLETED", 321, claimed.plusMillis(321));
        store.recordPublicationGate("run-1", "task-1", claimed.plusMillis(322));
        store.recordPdf("run-1", "task-1", claimed.plusMillis(323), claimed.plusMillis(400), 77);
        store.recordLeaseWait(task, 12, claimed.plusMillis(401));
        store.recordDeadLetter(task, claimed.plusMillis(402));

        assertThat(enqueued.get().enqueuedAt()).isEqualTo(created);
        assertThat(enqueued.get().submittedAt()).isEqualTo(created);
        assertThat(claim.get().runId()).isEqualTo("run-1");
        assertThat(claim.get().queueWaitMs()).isEqualTo(2_000L);
        assertThat(claim.get().retryCount()).isEqualTo(2);
        assertThat(terminal.get().ackLatencyMs()).isEqualTo(321L);
        assertThat(terminal.get().completedAt()).isNotNull();
        assertThat(terminal.get().failedAt()).isNull();
        assertThat(publication.get().publicationGateAt()).isNotNull();
        assertThat(pdf.get().xelatexStartedAt()).isNotNull();
        assertThat(pdf.get().elapsedMs()).isEqualTo(77L);
        assertThat(lease.get().elapsedMs()).isEqualTo(12L);
        assertThat(dlq.get().xelatexStartedAt()).isNotNull();
    }
}
