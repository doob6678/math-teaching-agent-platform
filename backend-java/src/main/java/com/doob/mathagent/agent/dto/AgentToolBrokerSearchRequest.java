package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Request from the Python AI runtime for one permission-scoped evidence search. */
public record AgentToolBrokerSearchRequest(
        @NotBlank String runId,
        String tenantId,
        String subjectType,
        String subjectId,
        @NotBlank String query,
        @Min(1) @Max(20) int limit) {
}
