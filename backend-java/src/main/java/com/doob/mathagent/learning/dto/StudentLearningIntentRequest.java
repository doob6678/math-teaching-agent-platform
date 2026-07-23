package com.doob.mathagent.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Natural-language request that the learning router maps to one existing student workflow. */
public record StudentLearningIntentRequest(
        @NotBlank @Size(max = 1000) String message) {
}
