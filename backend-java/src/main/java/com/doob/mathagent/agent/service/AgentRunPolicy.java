package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import java.util.Set;

/**
 * Server-side agent policy shared by planning and execution.
 */
public final class AgentRunPolicy {

    private static final List<AgentDefinition> AGENTS = List.of(
            new AgentDefinition(
                    "StudentTutorAgent",
                    Set.of("student"),
                    Set.of("tool:search:textbook", "tool:student:progress:read"),
                    Set.of("PUBLIC_TEXTBOOK", "STUDENT_PRIVATE", "MATH_VIP"),
                    false),
            new AgentDefinition(
                    "TeacherAssistantAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:search:textbook", "tool:search:private", "tool:student:progress:read"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    false),
            new AgentDefinition(
                    "CoursewareAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:courseware:generate", "tool:search:private", "tool:search:textbook"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    true),
            new AgentDefinition(
                    "QualityCheckAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:quality:check"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    false),
            new AgentDefinition(
                    "HandoutFormatterAgent",
                    Set.of("teacher", "admin"),
                    Set.of("tool:handout:format"),
                    Set.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                    false));

    private AgentRunPolicy() {
    }

    /**
     * Resolves the requested agent and verifies that the backend subject role may use it.
     */
    public static AgentDefinition resolveAgent(AgentRunPlanRequest request, RequestSubject subject) {
        String requested = request.agentCode().isBlank() ? defaultAgent(request, subject) : request.agentCode();
        AgentDefinition agent = agentByCode(requested);
        if (!agent.allowedRoles().contains(subject.subjectType())) {
            throw new IllegalArgumentException("Agent subject not allowed: " + subject.subjectType());
        }
        return agent;
    }

    /**
     * Returns an agent definition by code.
     */
    public static AgentDefinition agentByCode(String agentCode) {
        return AGENTS.stream()
                .filter(candidate -> candidate.code().equals(agentCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported agent code: " + agentCode));
    }

    /**
     * Selects a conservative default agent when callers omit an explicit agent code.
     */
    private static String defaultAgent(AgentRunPlanRequest request, RequestSubject subject) {
        if ("teacher".equals(subject.subjectType()) || "admin".equals(subject.subjectType())) {
            return request.taskType().contains("courseware") ? "CoursewareAgent" : "TeacherAssistantAgent";
        }
        return "StudentTutorAgent";
    }

    /**
     * Static agent definition used until definitions move to MySQL.
     *
     * @param code stable agent code
     * @param allowedRoles backend roles allowed to execute the agent
     * @param allowedToolScopes tool scopes this agent may call
     * @param allowedDataScopes data scopes this agent may read
     * @param highValueRequired whether execution always requires capability protection
     */
    public record AgentDefinition(
            String code,
            Set<String> allowedRoles,
            Set<String> allowedToolScopes,
            Set<String> allowedDataScopes,
            boolean highValueRequired) {
    }
}
