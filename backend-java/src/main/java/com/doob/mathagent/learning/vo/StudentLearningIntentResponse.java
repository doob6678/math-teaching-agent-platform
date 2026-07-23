package com.doob.mathagent.learning.vo;

/** Safe intent-routing result; it contains no model prompt, answer, or hidden question-bank fields. */
public record StudentLearningIntentResponse(
        String intentCode,
        double confidence,
        String knowledgePointId,
        String knowledgePointName,
        String suggestedApi,
        String recognizedBy) {
}
