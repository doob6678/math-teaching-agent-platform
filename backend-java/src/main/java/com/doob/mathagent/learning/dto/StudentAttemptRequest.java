package com.doob.mathagent.learning.dto;

import java.util.List;

/** Request created by the real answer-submission flow. */
public record StudentAttemptRequest(
        String questionId,
        String questionText,
        List<String> knowledgePointIds,
        boolean correct,
        long responseTimeMs) { }
