/**
 * MCP tools that the frontend lets a teacher request for exposure.
 * Backend policy still performs the final allowlist filtering by key profile and session role.
 */
export const MCP_TOOL_OPTIONS = [
  "search_textbook_evidence",
  "search_teacher_resource_evidence",
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
  "download_feishu_resource",
] as const;

/**
 * MCP tool descriptors are only shown when the backend has a real execution endpoint.
 * Keep this list empty instead of advertising future tools that would fail at runtime.
 */
export const MCP_PROTECTED_TOOL_OPTIONS = [] as const;

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
  get_teaching_ai_trace: {
    label: "Teaching AI trace",
    badge: "read-only",
    note: "Owned task diagnostics.",
  },
  get_ai_diagnostic_summary: {
    label: "AI diagnostics",
    badge: "read-only",
    note: "Retry and fallback summary.",
  },
  get_multi_agent_writing_trace: {
    label: "Writing trace",
    badge: "read-only",
    note: "Visible writing workflow trace.",
  },
  plan_agent_run: {
    label: "Agent planning",
    badge: "read-only",
    note: "Returns routing and ReAct plan only.",
  },
  start_multi_agent_writing: {
    label: "Start writing",
    badge: "protected",
    note: "Runs backend multi-agent writing.",
  },
  get_multi_agent_writing_status: {
    label: "Writing status",
    badge: "read-only",
    note: "Recover workflow progress.",
  },
  get_multi_agent_writing_artifact: {
    label: "Writing artifact",
    badge: "read-only",
    note: "Read generated handout content.",
  },
  export_multi_agent_writing_artifact: {
    label: "Export artifact",
    badge: "protected",
    note: "Exports markdown, LaTeX, or ZIP.",
  },
  resume_multi_agent_writing: {
    label: "Resume writing",
    badge: "protected",
    note: "Resumes failed writing workflow.",
  },
  discover_feishu_resources: {
    label: "Find Feishu",
    badge: "read-only",
    note: "Lists or searches Feishu candidates.",
  },
  download_feishu_resource: {
    label: "Download Feishu",
    badge: "protected",
    note: "Downloads, parses, and indexes resource.",
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
