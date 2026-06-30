/**
 * MCP tools that the frontend lets a teacher request for exposure.
 * Backend policy still performs the final allowlist filtering by key profile and session role.
 */
export const MCP_TOOL_OPTIONS = [
  "search_textbook_evidence",
  "search_teacher_resource_evidence",
] as const;

/**
 * MCP tools shown as future/protected capabilities. They are not sent in the default
 * copyable configuration because protected execution is not implemented for MCP yet.
 */
export const MCP_PROTECTED_TOOL_OPTIONS = [
  "plan_agent_run",
  "create_teaching_task",
  "export_handout_pdf",
  "list_teacher_resources",
] as const;

export interface McpToolOptionMeta {
  label: string;
  badge: "read-only" | "protected";
  note: string;
}

export const MCP_TOOL_OPTION_META: Record<string, McpToolOptionMeta> = {
  search_textbook_evidence: {
    label: "Textbook evidence",
    badge: "read-only",
    note: "Public textbook snippets.",
  },
  search_teacher_resource_evidence: {
    label: "Teacher resource evidence",
    badge: "read-only",
    note: "Visible Feishu/resource blocks.",
  },
  plan_agent_run: {
    label: "Agent planning",
    badge: "protected",
    note: "Kept out of MCP JSON.",
  },
  create_teaching_task: {
    label: "Create teaching task",
    badge: "protected",
    note: "Requires capability flow.",
  },
  export_handout_pdf: {
    label: "Export handout PDF",
    badge: "protected",
    note: "Requires capability flow.",
  },
  list_teacher_resources: {
    label: "List resources",
    badge: "protected",
    note: "Metadata-only; closed until execution is reviewed.",
  },
};

/**
 * MCP prompts that the frontend lets a teacher request for exposure.
 * Backend filtering prevents a student-profile key from receiving teacher-only prompts.
 */
export const MCP_PROMPT_OPTIONS = [
  "teacher_handout_writer",
  "student_blank_handout_writer",
  "solution_reviewer",
] as const;

/**
 * Creates the default exposure selection used when opening the MCP configuration panel.
 */
export function defaultMcpExposureSelection() {
  return {
    tools: [...MCP_TOOL_OPTIONS] as string[],
    prompts: [...MCP_PROMPT_OPTIONS] as string[],
  };
}

/**
 * Toggles one MCP option while preserving stable option ordering for deterministic requests.
 */
export function toggleMcpExposureOption(
  current: string[],
  option: string,
  checked: boolean,
  order: readonly string[],
) {
  const next = new Set(current);
  if (checked) {
    next.add(option);
  } else {
    next.delete(option);
  }
  return order.filter((item) => next.has(item));
}
