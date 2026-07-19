package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.Optional;

/**
 * Store for durable multi-agent writing workflow status snapshots.
 */
public interface MultiAgentWritingWorkflowStore {

    /**
     * Saves or replaces one workflow status snapshot.
     *
     * @param record workflow status snapshot
     * @return normalized saved snapshot
     */
    MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record);

    /**
     * Finds one workflow when the backend subject may view it.
     *
     * @param workflowId backend workflow id
     * @param subject backend subject
     * @return visible workflow snapshot when found
     */
    Optional<MultiAgentWritingWorkflowRecord> findVisible(String workflowId, RequestSubject subject);
}
