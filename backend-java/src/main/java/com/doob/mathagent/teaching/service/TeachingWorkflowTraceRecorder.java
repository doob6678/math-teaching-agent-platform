package com.doob.mathagent.teaching.service;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.TeachingRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists one safe execution record for each real teaching-DAG boundary.
 *
 * <p>The task snapshot is optimized for the UI, while these rows make the parent orchestrator, retrieval workers,
 * question branches, and render stages independently auditable. Only stable names and evidence references are
 * retained; prompts, raw OCR, and model output remain outside the trace store.</p>
 */
final class TeachingWorkflowTraceRecorder {

    private static final AgentRunExecuteResponse.TokenUsage NO_USAGE =
            new AgentRunExecuteResponse.TokenUsage(0, 0, 0);

    private final AgentTraceStore traceStore;

    TeachingWorkflowTraceRecorder(AgentTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    /** Records a completed node after its implementation has actually returned. */
    void completed(String taskId, TeachingRequestContext context, String nodeCode, String agentCode,
            List<TeachingEvidence> evidence, long elapsedMs, String message) {
        save(taskId, context, nodeCode, agentCode, "COMPLETED", evidence, elapsedMs, message);
    }

    /** Records a failed node before the exception is propagated to the task recovery path. */
    void failed(String taskId, TeachingRequestContext context, String nodeCode, String agentCode,
            List<TeachingEvidence> evidence, long elapsedMs, Throwable failure) {
        String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "教学节点执行失败" : failure.getMessage().strip();
        save(taskId, context, nodeCode, agentCode, "FAILED", evidence, elapsedMs, message);
    }

    private void save(String taskId, TeachingRequestContext context, String nodeCode, String agentCode,
            String status, List<TeachingEvidence> evidence, long elapsedMs, String message) {
        if (traceStore == null || context == null || taskId == null || taskId.isBlank()) return;
        TeachingRequestContext normalized = context.normalize();
        List<String> refs = evidence == null ? List.of() : evidence.stream().filter(item -> item != null)
                .map(TeachingWorkflowService::evidenceRef).filter(value -> value != null && !value.isBlank())
                .distinct().toList();
        String node = nodeCode == null || nodeCode.isBlank() ? "unknown" : nodeCode.strip();
        String agent = agentCode == null || agentCode.isBlank() ? "TeachingAgent" : agentCode.strip();
        traceStore.save(new AgentTraceRecord(
                UUID.randomUUID().toString(), taskId + ":" + node, Instant.now(), normalized.tenantId(),
                normalized.subjectType(), normalized.subjectId(), agent, "workflow", "deterministic", status, 0.0d,
                List.of("workflow:" + node), List.of("PUBLIC_TEXTBOOK", "QUESTION_BANK", "TEACHER_RESOURCE"), refs,
                List.of(new AgentRunExecuteResponse.StageTiming(node, Math.max(0L, elapsedMs))), NO_USAGE,
                message == null ? "" : message.strip(), List.of()));
    }
}
