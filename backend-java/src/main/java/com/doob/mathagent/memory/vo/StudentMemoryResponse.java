package com.doob.mathagent.memory.vo;

import java.util.List;

/**
 * Response for student memory reuse checks.
 *
 * @param reused whether a previous answer was reused
 * @param memoryId reused or written memory id
 * @param reuseScope private or public reuse scope
 * @param answer reusable answer text
 * @param similarity similarity score from 0 to 1
 * @param reason decision reason for audit and UI display
 * @param timings stage timing records for performance visibility
 */
public record StudentMemoryResponse(
        boolean reused,
        String memoryId,
        String reuseScope,
        String answer,
        double similarity,
        String reason,
        List<StageTiming> timings) {

    /**
     * Timing record for a single memory pipeline stage.
     *
     * @param stage stage code, such as normalize, similarity_match, or reuse_decision
     * @param elapsedMs elapsed milliseconds
     */
    public record StageTiming(String stage, long elapsedMs) {
    }
}
