package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** Opaque asset-only request; a Python Agent never receives a storage path or arbitrary URL capability. */
public record AgentToolBrokerAssetRequest(
        @NotBlank String runId,
        @NotBlank String tenantId,
        @NotBlank String subjectType,
        @NotBlank String subjectId,
        @NotBlank String assetId) {
}
