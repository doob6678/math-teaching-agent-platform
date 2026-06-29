/**
 * MCP tools that the frontend lets a teacher request for exposure.
 * Backend policy still performs the final allowlist filtering by key profile and session role.
 */
export const MCP_TOOL_OPTIONS = [
  "search_textbook_evidence",
  "plan_agent_run",
  "create_teaching_task",
  "export_handout_pdf",
  "list_teacher_resources",
] as const;

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
