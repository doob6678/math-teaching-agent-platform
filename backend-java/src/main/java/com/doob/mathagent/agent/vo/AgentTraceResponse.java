package com.doob.mathagent.agent.vo;

import java.time.Instant;
import java.util.List;

/**
 * Safe agent trace response for status recovery and monitoring.
 *
 * @param traceId trace id
 * @param planId linked plan id
 * @param createdAt trace creation time
 * @param tenantId backend tenant id
 * @param subjectType backend subject role
 * @param subjectId backend subject id
 * @param agentCode executed agent code
 * @param providerName selected provider
 * @param modelCode selected model
 * @param status execution status
 * @param estimatedCost estimated local cost
 * @param allowedToolScopes allowed tool scopes recorded for audit
 * @param allowedDataScopes allowed data scopes recorded for audit
 * @param evidenceRefs evidence references used by this run
 */
public record AgentTraceResponse(
        String traceId,
        String planId,
        Instant createdAt,
        String tenantId,
        String subjectType,
        String subjectId,
        String agentCode,
        String providerName,
        String modelCode,
        String status,
        double estimatedCost,
        List<String> allowedToolScopes,
        List<String> allowedDataScopes,
        List<String> evidenceRefs) {
}
