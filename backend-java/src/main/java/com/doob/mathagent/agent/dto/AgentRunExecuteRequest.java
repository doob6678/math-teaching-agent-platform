package com.doob.mathagent.agent.dto;

import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import java.util.List;

/**
 * Request for executing a previously planned AI agent run.
 *
 * @param plan frontend-returned plan snapshot; the backend still rechecks owner and high-value policy
 * @param userInputSummary short task summary for trace audit, never treated as user identity
 * @param evidenceRefs evidence ids or resource anchors selected for this run
 * @param dryRun must be false; production entrypoints reject trace-only execution
 */
public record AgentRunExecuteRequest(
        AgentRunPlanResponse plan,
        String userInputSummary,
        List<String> evidenceRefs,
        boolean dryRun) {

    /**
     * Returns a request with null-safe collections and stripped text fields.
     */
    public AgentRunExecuteRequest normalize() {
        if (plan == null) {
            throw new IllegalArgumentException("Agent execution plan is required");
        }
        return new AgentRunExecuteRequest(
                plan,
                safeText(userInputSummary),
                evidenceRefs == null ? List.of() : evidenceRefs.stream().map(AgentRunExecuteRequest::safeText).toList(),
                dryRun);
    }

    /**
     * Strips nullable text for trace storage.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
