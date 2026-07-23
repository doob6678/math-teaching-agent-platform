package com.doob.mathagent.learning.vo;

import com.doob.mathagent.learning.StudentKnowledgeMastery;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import java.util.List;

/** API payloads for the learning loop. */
public final class StudentLearningResponse {
    private StudentLearningResponse() { }
    public record Attempt(
            String attemptId, List<StudentKnowledgeMastery> updatedMastery, List<StudentKnowledgeMastery> weakPoints) { }
    public record Recommendation(
            QuestionBankItemResponse question, String knowledgePointId, int weaknessLevel) { }
}
