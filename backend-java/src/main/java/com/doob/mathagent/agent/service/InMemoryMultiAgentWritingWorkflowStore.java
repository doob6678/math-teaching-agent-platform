package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * In-memory workflow status store used when MySQL persistence is disabled.
 */
@Service
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryMultiAgentWritingWorkflowStore implements MultiAgentWritingWorkflowStore {

    private final Map<String, MultiAgentWritingWorkflowRecord> records = new ConcurrentHashMap<>();

    /**
     * Saves or replaces a workflow by workflow id.
     */
    @Override
    public MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record) {
        MultiAgentWritingWorkflowRecord normalized = record.normalize();
        records.put(normalized.workflowId(), normalized);
        return normalized;
    }

    /**
     * Reads a workflow only when tenant and owner visibility allow it.
     */
    @Override
    public Optional<MultiAgentWritingWorkflowRecord> findVisible(String workflowId, RequestSubject subject) {
        RequestSubject normalizedSubject = subject.normalize();
        if (workflowId == null || workflowId.isBlank() || normalizedSubject.subjectId().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(workflowId.strip()))
                .filter(record -> canView(record, normalizedSubject));
    }

    /**
     * Checks tenant and owner visibility for a workflow.
     */
    private static boolean canView(MultiAgentWritingWorkflowRecord record, RequestSubject subject) {
        if (!record.tenantId().equals(subject.tenantId())) {
            return false;
        }
        return "admin".equals(subject.subjectType())
                || (record.subjectType().equals(subject.subjectType()) && record.subjectId().equals(subject.subjectId()));
    }
}
