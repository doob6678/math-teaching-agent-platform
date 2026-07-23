package com.doob.mathagent.learning.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Teacher request for an asynchronous handout assembled from real weak-point and question-bank evidence. */
public record TargetedHandoutRequest(
        @NotBlank @Size(max = 128) String clientRequestId,
        @Size(max = 128) String studentId,
        @Size(max = 128) String knowledgePointId,
        @Min(1) int questionLimit,
        @Size(max = 128) String handoutTemplateCode,
        @Min(1) int evidenceLimit) {

    /** Keeps old/simple callers useful while applying bounded defaults at the service boundary. */
    public TargetedHandoutRequest(String clientRequestId, String studentId, String knowledgePointId, int questionLimit) {
        this(clientRequestId, studentId, knowledgePointId, questionLimit, null, 5);
    }
}
