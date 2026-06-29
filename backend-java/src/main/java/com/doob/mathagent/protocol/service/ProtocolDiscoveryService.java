package com.doob.mathagent.protocol.service;

import com.doob.mathagent.protocol.vo.A2aAgentCardResponse;
import com.doob.mathagent.protocol.vo.McpToolDescriptor;
import com.doob.mathagent.protocol.vo.McpToolInputSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Provides read-only MCP and A2A discovery metadata.
 */
@Service
public class ProtocolDiscoveryService {

    private static final List<String> TEACHING_ROLES = List.of("student", "teacher", "admin");
    private static final List<String> TEACHER_ROLES = List.of("teacher", "admin");

    /**
     * Returns MCP tool descriptors without exposing execution endpoints.
     */
    public List<McpToolDescriptor> mcpTools() {
        return List.of(
                new McpToolDescriptor(
                        "search_textbook_evidence",
                        "Search textbook evidence",
                        "Search public textbook evidence with backend tenant and role checks.",
                        true,
                        false,
                        TEACHING_ROLES,
                        "query:basic",
                        "low",
                        false,
                        true,
                        schema(
                                fields(
                                        field("query", "string", "Search text submitted by the agent."),
                                        field("limit", "integer", "Maximum evidence snippets to return.")),
                                "query")),
                new McpToolDescriptor(
                        "plan_agent_run",
                        "Plan agent run",
                        "Plan a teaching agent run and apply backend tool policy decisions.",
                        true,
                        false,
                        TEACHING_ROLES,
                        "agent:plan",
                        "medium",
                        false,
                        true,
                        schema(
                                fields(
                                        field("agentCode", "string", "Backend agent code to plan."),
                                        field("requestedToolScopes", "array", "Tool scopes requested by the client."),
                                        field("disabledToolScopes", "array", "Tool scopes disabled by the user.")),
                                "agentCode")),
                new McpToolDescriptor(
                        "create_teaching_task",
                        "Create teaching task",
                        "Create a resumable teaching DAG task after one-time capability verification.",
                        false,
                        false,
                        TEACHING_ROLES,
                        "teaching:write",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("question", "string", "Student question or teaching goal."),
                                        field("goal", "string", "Expected learning objective."),
                                        field("difficulty", "integer", "Target difficulty from 1 to 5.")),
                                "question")),
                new McpToolDescriptor(
                        "export_handout_pdf",
                        "Export handout PDF",
                        "Export an owned teacher or student handout PDF after capability verification.",
                        false,
                        false,
                        TEACHING_ROLES,
                        "teaching:export",
                        "high",
                        true,
                        true,
                        schema(
                                fields(
                                        field("taskId", "string", "Owned teaching task id."),
                                        field("version", "string", "Handout version: teacher or student.")),
                                "taskId",
                                "version")),
                new McpToolDescriptor(
                        "list_teacher_resources",
                        "List teacher resources",
                        "List teacher-visible resource metadata without raw local file paths.",
                        true,
                        false,
                        TEACHER_ROLES,
                        "teacher-resource:read",
                        "low",
                        false,
                        true,
                        schema(
                                fields(field("scope", "string", "Optional resource scope filter.")))));
    }

    /**
     * Returns the A2A Agent Card metadata for this platform.
     */
    public A2aAgentCardResponse a2aAgentCard() {
        return new A2aAgentCardResponse(
                "Math Agent RAG",
                "Math teaching RAG platform for textbook evidence retrieval, teaching task planning, handout generation, and student learning support.",
                "/api/a2a",
                "0.3.0",
                "jsonrpc",
                new A2aAgentCardResponse.Capabilities(false, false, true),
                List.of(
                        skill(
                                "textbook_evidence_retrieval",
                                "Textbook Evidence Retrieval",
                                "Retrieves textbook evidence with backend role and tenant boundaries.",
                                "rag",
                                "textbook"),
                        skill(
                                "teaching_task_planning",
                                "Teaching Task Planning",
                                "Plans resumable DAG and ReAct teaching workflows.",
                                "dag",
                                "react"),
                        skill(
                                "teacher_student_handout_generation",
                                "Teacher and Student Handout Generation",
                                "Describes teacher and student handout generation capability with protected exports.",
                                "latex",
                                "pdf",
                                "handout"),
                        skill(
                                "agent_run_planning",
                                "Agent Run Planning",
                                "Plans multi-agent runs and applies backend tool preference policy.",
                                "agent",
                                "tool-policy")),
                List.of(
                        new A2aAgentCardResponse.SecurityScheme(
                                "sa-token-session",
                                "session",
                                "Authenticated platform users are resolved by backend Sa-Token session state."),
                        new A2aAgentCardResponse.SecurityScheme(
                                "capability-token",
                                "one-time-token",
                                "High-value operations require request-hash-bound one-time capability tokens and audit.")));
    }

    /**
     * Creates one A2A skill record.
     */
    private static A2aAgentCardResponse.Skill skill(
            String id,
            String name,
            String description,
            String... tags) {
        return new A2aAgentCardResponse.Skill(id, name, description, List.of(tags));
    }

    /**
     * Creates a JSON schema object for MCP tool arguments.
     */
    private static McpToolInputSchema schema(
            Map<String, Map<String, Object>> properties,
            String... required) {
        return new McpToolInputSchema("object", properties, List.of(required));
    }

    /**
     * Creates a stable ordered field map.
     */
    @SafeVarargs
    private static Map<String, Map<String, Object>> fields(Map.Entry<String, Map<String, Object>>... entries) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            fields.put(entry.getKey(), entry.getValue());
        }
        return fields;
    }

    /**
     * Creates one JSON schema field definition.
     */
    private static Map.Entry<String, Map<String, Object>> field(String name, String type, String description) {
        return Map.entry(name, Map.of("type", type, "description", description));
    }
}
