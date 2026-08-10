package com.doob.mathagent.agent.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Requeues expired task leases through the durable outbox instead of publishing inside a recovery loop. */
@Component
@ConditionalOnProperty(name = "math-agent.rabbitmq.listeners-enabled", havingValue = "true")
public class AgentWorkerLeaseRecovery {
    private final AgentWorkerTaskDispatchService dispatchService;
    public AgentWorkerLeaseRecovery(AgentWorkerTaskDispatchService dispatchService) { this.dispatchService = dispatchService; }
    @Scheduled(fixedDelayString = "${math-agent.agent-worker.recovery-milliseconds:30000}")
    public void recover() { dispatchService.requeueExpiredLeases(); }
}
