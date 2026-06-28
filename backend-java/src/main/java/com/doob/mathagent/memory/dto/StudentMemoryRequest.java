package com.doob.mathagent.memory.dto;

/**
 * Request for student memory remember/reuse operations.
 *
 * @param tenantId tenant id used to isolate schools or organizations
 * @param viewerRole current viewer role, such as student, teacher, or admin
 * @param studentId student id that owns private memory
 * @param questionText normalized or raw student question text
 * @param answerText answer text to remember; empty when only querying reuse
 * @param knowledgePointName optional knowledge point used to improve similarity matching
 * @param memoryScope private means student-owned; public means reusable across students in the same tenant
 * @param bypassReuse whether the caller explicitly asks to skip reuse and regenerate
 */
public record StudentMemoryRequest(
        String tenantId,
        String viewerRole,
        String studentId,
        String questionText,
        String answerText,
        String knowledgePointName,
        String memoryScope,
        boolean bypassReuse) {

    /**
     * Returns a normalized memory request with safe local defaults.
     *
     * @return normalized request
     */
    public StudentMemoryRequest normalize() {
        return new StudentMemoryRequest(
                textOrDefault(tenantId, "default"),
                textOrDefault(viewerRole, "student").toLowerCase(),
                textOrDefault(studentId, "local-student"),
                textOrDefault(questionText, ""),
                answerText == null ? null : answerText.strip(),
                knowledgePointName == null ? "" : knowledgePointName.strip(),
                textOrDefault(memoryScope, "private").toLowerCase(),
                bypassReuse);
    }

    /**
     * Returns whether the request can be stored as a memory entry.
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
