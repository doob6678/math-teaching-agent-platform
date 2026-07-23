package com.doob.mathagent.agent.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Republishes only expired leases, allowing another live Worker to safely take over after a crash. */
@Component
public class AgentWorkerLeaseRecovery {
    private final AgentWorkerTaskStore store; private final AgentWorkerTaskPublisher publisher;
    public AgentWorkerLeaseRecovery(AgentWorkerTaskStore store, AgentWorkerTaskPublisher publisher) { this.store=store; this.publisher=publisher; }
    @Scheduled(fixedDelayString = "${math-agent.agent-worker.recovery-milliseconds:30000}")
    public void recover() { store.reclaimExpired().forEach(publisher::publish); }
}
