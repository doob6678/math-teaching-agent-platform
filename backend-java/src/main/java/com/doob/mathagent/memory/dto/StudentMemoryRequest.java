package com.doob.mathagent.memory.dto;

/**
 * Request body for student memory remember/reuse operations.
 *
 * @param questionText normalized or raw student question text
 * @param answerText answer text to remember; empty when only querying reuse
 * @param knowledgePointName optional knowledge point used to improve similarity matching
 * @param memoryScope private means student-owned; public means reusable only after server-side role check
 * @param bypassReuse whether the caller explicitly asks to skip reuse and regenerate
 */
public record StudentMemoryRequest(
        String questionText,
        String answerText,
        String knowledgePointName,
        String memoryScope,
        boolean bypassReuse) {

    /**
     * Returns a normalized request body without adding identity defaults.
     *
     * @return normalized request body
     */
    public StudentMemoryRequest normalize() {
        return new StudentMemoryRequest(
                textOrDefault(questionText, ""),
                answerText == null ? null : answerText.strip(),
                knowledgePointName == null ? "" : knowledgePointName.strip(),
                textOrDefault(memoryScope, "private").toLowerCase(),
                bypassReuse);
    }

    /**
     * Returns whether the request body has enough content to be stored.
     *
     * @return true when question and answer are present
     */
    public boolean rememberable() {
        StudentMemoryRequest normalized = normalize();
        return !normalized.questionText.isBlank()
                && normalized.answerText != null
                && !normalized.answerText.isBlank();
    }

    /**
     * Returns stripped text or a fallback when blank.
     *
     * @param value input text
     * @param defaultValue fallback text
     * @return normalized text
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
