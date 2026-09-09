package com.doob.mathagent.agent.worker;

import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundary for every durable Agent Worker dispatch state transition. */
@Service
public class AgentWorkerTaskDispatchService {
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final AgentWorkerTaskStore taskStore;
    private final AgentWorkerTaskOutboxStore outboxStore;

    public AgentWorkerTaskDispatchService(
            MultiAgentWritingWorkflowStore workflowStore,
            AgentWorkerTaskStore taskStore,
            AgentWorkerTaskOutboxStore outboxStore) {
        this.workflowStore = workflowStore;
        this.taskStore = taskStore;
        this.outboxStore = outboxStore;
    }

    /** Commits workflow ownership, the queued task, and its first broker event as one database transaction. */
    @Transactional
    public MultiAgentWritingWorkflowRecord submit(
            MultiAgentWritingWorkflowRecord workflow, String agentCode, String stageCode, String requestJson) {
        MultiAgentWritingWorkflowRecord saved = workflowStore.save(workflow);
        AgentWorkerTask task = taskStore.create(
                saved.workflowId(), saved.tenantId(), agentCode, stageCode, requestJson);
        outboxStore.enqueue(task);
        return saved;
    }

    /**
     * Commits an explicit same-workflow recovery transition plus its durable Worker command.
     *
     * <p>Recovery is intentionally separate from normal submission because a completed workflow may be moved back to
     * RUNNING only after the caller has verified that new authorized evidence is available.</p>
     */
    @Transactional
    public MultiAgentWritingWorkflowRecord submitRecovery(
            MultiAgentWritingWorkflowRecord workflow, String agentCode, String stageCode, String requestJson) {
        MultiAgentWritingWorkflowRecord saved = workflowStore.requeue(workflow);
        AgentWorkerTask task = taskStore.create(
                saved.workflowId(), saved.tenantId(), agentCode, stageCode, requestJson);
        outboxStore.enqueue(task);
        return saved;
    }

    /**
     * Commits one durable Worker command and returns the created task row.
     *
     * <p>动画讲题等非讲义 workload 复用同一 agent_worker_task + outbox 通道：所有权（tenant 与 subject）仍记在
     * 既有 multi_agent_writing_workflow 行上——agent_worker_task.workflow_id 有外键指向该表，任务行因此天然可鉴权、
     * 可重投递复原身份，无需扩表。与 {@link #submit} 的差别只在返回任务本身：前端轮询句柄与产物目录都以 task_id 为键。
     * requestJson 形状由具体 stage 的服务自定（消费者按 stage_code 分流解析），调度层保持不透明。</p>
     */
    @Transactional
    public AgentWorkerTask create(
            MultiAgentWritingWorkflowRecord workflow, String agentCode, String stageCode, String requestJson) {
        MultiAgentWritingWorkflowRecord saved = workflowStore.save(workflow);
        AgentWorkerTask task = taskStore.create(
                saved.workflowId(), saved.tenantId(), agentCode, stageCode, requestJson);
        outboxStore.enqueue(task);
        return task;
    }

    public boolean handleFailure(AgentWorkerTask task, String errorSummary, int maximumAttempts) {
        AgentWorkerTask retry = taskStore.failOrRequeue(task, errorSummary, maximumAttempts);
        if (retry != null) {
            outboxStore.enqueue(retry);
            return true;
        }
        if (task.attempt() >= maximumAttempts && taskStore.isFailed(task.taskId())) {
            markTerminalWorkflowFailure(task, errorSummary);
        }
        return false;
    }

    /** Persists terminal workflow state in the same transaction as the terminal task transition. */
    private void markTerminalWorkflowFailure(AgentWorkerTask task, String errorSummary) {
        MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(task.workflowId())
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        String safe = safe(errorSummary);
        workflowStore.save(new MultiAgentWritingWorkflowRecord(
                workflow.workflowId(), workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "FAILED",
                workflow.createdAt(), Instant.now(), workflow.stages(), workflow.totalUsage(),
                "Python handout task failed after retries: " + safe));
    }

    /** Requeues expired leases and creates exactly one event for each newly incremented dispatch generation. */
    @Transactional
    public int requeueExpiredLeases() {
        List<AgentWorkerTask> reclaimed = taskStore.reclaimExpired();
        reclaimed.forEach(outboxStore::enqueue);
        return reclaimed.size();
    }

    /** Repairs only old queued generations that have no durable event for their current dispatch version. */
    @Transactional
    public int reconcileOrphanQueued(Instant olderThan, int limit) {
        List<AgentWorkerTask> orphans = outboxStore.findOrphanQueued(olderThan, limit);
        orphans.forEach(outboxStore::enqueue);
        return orphans.size();
    }

    /** Restores events left PUBLISHING by a process crash; duplicate delivery remains safe through task claim CAS. */
    @Transactional
    public int recoverExpiredPublishing(Instant now) {
        return outboxStore.recoverExpiredPublishing(now);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "Worker task failed";
        }
        return value.substring(0, Math.min(300, value.length())).replaceAll("[\\r\\n]+", " ");
    }
}
