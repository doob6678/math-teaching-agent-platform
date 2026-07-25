package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** Request for authorized block expansion; no filesystem path is accepted or returned. */
public record AgentToolBrokerReadRequest(
        @NotBlank String runId,
        @NotBlank String tenantId,
        @NotBlank String subjectType,
        @NotBlank String subjectId,
        @NotBlank String capabilityToken,
        @NotBlank String documentId) {
}
