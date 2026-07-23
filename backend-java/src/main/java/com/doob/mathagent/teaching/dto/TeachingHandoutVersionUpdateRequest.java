package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Durable edit submitted for one generated handout version.
 *
 * @param latex complete LaTeX body for the selected version; the workflow service applies final student-safety and
 *     internal-instruction guards before persistence
 */
public record TeachingHandoutVersionUpdateRequest(
        @NotBlank @Size(max = 120_000) String latex) {

    /**
     * Normalizes only transport whitespace. Content filtering belongs to the workflow service so generated and edited
     * handouts always use the same security boundary.
     *
     * @return bounded, non-null LaTeX source
     */
    public String normalizedLatex() {
        return latex == null ? "" : latex.strip();
    }
}
