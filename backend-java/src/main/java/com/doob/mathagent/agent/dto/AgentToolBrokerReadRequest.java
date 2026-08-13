package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** Request for authorized block expansion; no filesystem path is accepted or returned. */
public record AgentToolBrokerReadRequest(
        @NotBlank String runId,
        String tenantId,
        String subjectType,
        String subjectId,
        @NotBlank String documentId) {
}
