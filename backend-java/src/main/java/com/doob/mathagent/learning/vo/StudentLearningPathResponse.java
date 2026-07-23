package com.doob.mathagent.learning.vo;

import java.util.List;

/** Deterministic learning path projected from mastery facts and visible prerequisite edges. */
public record StudentLearningPathResponse(String studentId, List<Step> steps, String generatedFrom) {
    public record Step(
            String knowledgePointId,
            String knowledgePointName,
            int masteryPercent,
            int weaknessLevel,
            String relationToNext,
            String recommendation) { }
}
