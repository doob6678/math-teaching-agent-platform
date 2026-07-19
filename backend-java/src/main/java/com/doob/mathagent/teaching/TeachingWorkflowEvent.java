package com.doob.mathagent.teaching;

import java.util.List;

/**
 * Recoverable teaching workflow event for UI progress and later agent_run_event persistence.
 *
 * @param eventId stable id within one task response
 * @param parentEventId parent event id; empty for root-level workflow events
 * @param sourceType producer type, such as system, agent, tool, or reviewer
 * @param sourceName producer name shown in progress UI
 * @param eventType stable event kind, such as plan, evidence, generation, or render
 * @param status event status, currently completed for persisted task snapshots
 * @param title short user-facing event title
 * @param summary safe summary without raw prompt, token, or debug leakage
 * @param artifactRefs evidence scopes or artifact versions produced by the event
 */
public record TeachingWorkflowEvent(
        String eventId,
        String parentEventId,
        String sourceType,
        String sourceName,
        String eventType,
        String status,
        String title,
        String summary,
        List<String> artifactRefs) {
}
