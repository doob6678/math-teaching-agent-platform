package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.Optional;

/**
 * Store for durable multi-agent writing workflow status snapshots.
 */
public interface MultiAgentWritingWorkflowStore {

    /**
     * Reads a workflow by opaque id for the authenticated internal Worker boundary.
     * Browser callers must continue using {@link #findVisible(String, RequestSubject)}.
     */
    default Optional<MultiAgentWritingWorkflowRecord> findByIdInternal(String workflowId) {
        return Optional.empty();
    }

    /**
     * Saves or replaces one workflow status snapshot.
     *
     * @param record workflow status snapshot
     * @return normalized saved snapshot
     */
    MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record);

    /**
     * Persists an explicit recovery transition for an existing workflow.
     *
     * <p>Unlike an ordinary snapshot save, this operation is allowed to move a completed workflow back to RUNNING
     * because the caller has already created a durable recovery task for the same workflow id.</p>
     *
     * @param record recovery snapshot
     * @return normalized saved snapshot
     */
    default MultiAgentWritingWorkflowRecord requeue(MultiAgentWritingWorkflowRecord record) {
        return save(record);
    }
    /**
     * Finds one workflow when the backend subject may view it.
     *
     * @param workflowId backend workflow id
     * @param subject backend subject
     * @return visible workflow snapshot when found
     */
    Optional<MultiAgentWritingWorkflowRecord> findVisible(String workflowId, RequestSubject subject);
}
