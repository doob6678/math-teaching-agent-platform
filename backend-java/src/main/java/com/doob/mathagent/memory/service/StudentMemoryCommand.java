package com.doob.mathagent.memory.service;

import com.doob.mathagent.memory.dto.StudentMemoryRequest;

/**
 * Server-side command for student memory operations.
 *
 * @param tenantId backend resolved tenant id used to isolate schools or organizations
 * @param viewerRole backend resolved role, such as student, teacher, or admin
 * @param studentId backend resolved user id that owns private memory
 * @param questionText normalized or raw student question text
 * @param answerText answer text to remember; empty when only querying reuse
 * @param knowledgePointName optional knowledge point used to improve similarity matching
 * @param memoryScope requested memory scope; unprivileged public writes are downgraded to private
 * @param bypassReuse whether the caller explicitly asks to skip reuse and regenerate
 */
public record StudentMemoryCommand(
        String tenantId,
        String viewerRole,
        String studentId,
        String questionText,
        String answerText,
        String knowledgePointName,
        String memoryScope,
        boolean bypassReuse) {

    /**
     * Builds a command from backend identity and request body fields.
     *
     * @param tenantId backend resolved tenant id
     * @param viewerRole backend resolved role
     * @param studentId backend resolved user id
     * @param request request body
     * @return server-side memory command
     */
    public static StudentMemoryCommand fromRequest(
            String tenantId,
            String viewerRole,
            String studentId,
            StudentMemoryRequest request) {
        StudentMemoryRequest normalized = request.normalize();
        return new StudentMemoryCommand(
                tenantId,
                viewerRole,
                studentId,
                normalized.questionText(),
                normalized.answerText(),
                normalized.knowledgePointName(),
                normalized.memoryScope(),
                normalized.bypassReuse());
    }

    /**
     * Returns a normalized command with safe local defaults for non-web tests.
     *
     * @return normalized command
     */
    public StudentMemoryCommand normalize() {
        return new StudentMemoryCommand(
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
     * Returns whether the command can be stored as a memory entry.
     *
     * @return true when question and answer are present
     */
    public boolean rememberable() {
        StudentMemoryCommand normalized = normalize();
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
