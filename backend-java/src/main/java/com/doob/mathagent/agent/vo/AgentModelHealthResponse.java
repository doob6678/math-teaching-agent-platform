package com.doob.mathagent.agent.vo;

import java.time.Instant;
import java.util.List;

/**
 * Frontend-safe AI provider health response. It never includes API keys, prompts, or raw model output.
 *
 * @param checkedAt backend time when the health check started
 * @param results per-provider health results
 */
public record AgentModelHealthResponse(
        Instant checkedAt,
        List<Result> results) {

    /**
     * One provider/model health result safe for display.
     *
     * @param providerName provider code
     * @param modelCode model code checked
     * @param configured whether backend credentials were configured
     * @param reachable whether the provider answered the health request
     * @param statusCode coarse HTTP-style status when known
     * @param elapsedMs elapsed milliseconds spent on this check
     * @param safeReason short safe status without raw provider response
     * @param checkedAt backend time when this provider check started
     */
    public record Result(
            String providerName,
            String modelCode,
            boolean configured,
            boolean reachable,
            Integer statusCode,
            long elapsedMs,
            String safeReason,
            Instant checkedAt) {
    }
}
