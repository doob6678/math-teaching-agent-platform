package com.doob.mathagent.protocol.service;

import java.util.List;

/**
 * Central MCP access policy derived from backend role identity.
 */
public final class McpAccessPolicy {

    private static final List<String> STUDENT_TOOLS = List.of(
            "search_textbook_evidence",
            "get_teaching_ai_trace",
            "get_ai_diagnostic_summary",
            "plan_agent_run");
    private static final List<String> TEACHER_TOOLS = List.of(
            "search_multi_source_evidence",
            "search_textbook_evidence",
            "search_teacher_resource_evidence",
            "list_teacher_resources",
            "read_teacher_resource_blocks",
            "get_teaching_ai_trace",
            "get_ai_diagnostic_summary",
            "get_multi_agent_writing_trace",
            "plan_agent_run",
            "start_multi_agent_writing",
            "get_multi_agent_writing_status",
            "get_multi_agent_writing_artifact",
            "export_multi_agent_writing_artifact",
            "resume_multi_agent_writing",
            "discover_feishu_resources",
            "download_feishu_resource");
    private static final List<String> STUDENT_PROMPTS = List.of(
            "student_blank_handout_writer",
            "solution_reviewer");
    private static final List<String> TEACHER_PROMPTS = List.of(
            "teacher_handout_writer",
            "student_blank_handout_writer",
            "solution_reviewer");
    private static final List<String> STUDENT_SCOPES = List.of(
            "PUBLIC_TEXTBOOK",
            "agent-trace:read",
            "agent:plan");
    private static final List<String> TEACHER_SCOPES = List.of(
            "PUBLIC_TEXTBOOK",
            "teacher-resource:read",
            "teacher-resource:sync-execute",
            "agent-trace:read",
            "agent:plan",
            "agent-writing:execute",
            "agent-writing:read",
            "agent-writing:export");

    private McpAccessPolicy() {
    }

    /**
     * Normalizes one backend subject role for MCP policy checks.
     */
    public static String normalizeProfile(String profile) {
        String normalized = profile == null ? "" : profile.strip().toLowerCase();
        return switch (normalized) {
            case "admin" -> "admin";
            case "teacher" -> "teacher";
            default -> "student";
        };
    }

    /**
     * Returns executable MCP tools allowed for one backend role.
     */
    public static List<String> toolsForProfile(String profile) {
        return isTeacherProfile(profile) ? TEACHER_TOOLS : STUDENT_TOOLS;
    }

    /**
     * Returns MCP prompts visible for one backend role.
     */
    public static List<String> promptsForProfile(String profile) {
        return isTeacherProfile(profile) ? TEACHER_PROMPTS : STUDENT_PROMPTS;
    }

    /**
     * Returns logical scopes granted to MCP keys bound to one backend role.
     */
    public static List<String> scopesForProfile(String profile) {
        return isTeacherProfile(profile) ? TEACHER_SCOPES : STUDENT_SCOPES;
    }

    private static boolean isTeacherProfile(String profile) {
        String normalized = normalizeProfile(profile);
        return "teacher".equals(normalized) || "admin".equals(normalized);
    }
}
