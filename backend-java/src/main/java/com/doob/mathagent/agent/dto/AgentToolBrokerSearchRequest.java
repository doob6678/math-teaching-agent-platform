package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Request from the Python AI runtime for one permission-scoped evidence search. */
public record AgentToolBrokerSearchRequest(
        @NotBlank String runId,
        @NotBlank String tenantId,
        @NotBlank String subjectType,
        @NotBlank String subjectId,
        @NotBlank String capabilityToken,
        @NotBlank String query,
        @Min(1) @Max(20) int limit) {
}
