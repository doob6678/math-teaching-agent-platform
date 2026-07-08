/**
 * Historical MCP exposure metadata retained for tests and UI labels.
 * The backend now owns the final allowlist and configuration generation.
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

export const MCP_PROTECTED_TOOL_OPTIONS = [] as const;

export interface McpToolOptionMeta {
  label: string;
  badge: "只读" | "需授权";
  note: string;
}

export const MCP_TOOL_OPTION_META: Record<string, McpToolOptionMeta> = {
  search_textbook_evidence: {
    label: "教材证据检索",
    badge: "只读",
    note: "检索公开教材片段。",
  },
  search_teacher_resource_evidence: {
    label: "教师资源检索",
    badge: "只读",
    note: "检索当前账号可见的教师资源片段。",
  },
  get_teaching_ai_trace: {
    label: "教学任务追踪",
    badge: "只读",
    note: "查看本人可见教学任务的模型调用与解析记录。",
  },
  get_ai_diagnostic_summary: {
    label: "AI 诊断汇总",
    badge: "只读",
    note: "查看重试、降级和恢复情况。",
  },
  get_multi_agent_writing_trace: {
    label: "讲义协作追踪",
    badge: "只读",
    note: "查看可见写作流程的阶段追踪。",
  },
  plan_agent_run: {
    label: "智能体预案",
    badge: "只读",
    note: "只返回规划结果，不直接启动任务。",
  },
  start_multi_agent_writing: {
    label: "启动讲义协作",
    badge: "需授权",
    note: "启动后端真实多智能体写作流程。",
  },
  get_multi_agent_writing_status: {
    label: "讲义流程状态",
    badge: "只读",
    note: "恢复并查看写作流程进度。",
  },
  get_multi_agent_writing_artifact: {
    label: "读取讲义成果",
    badge: "只读",
    note: "读取已生成讲义正文。",
  },
  export_multi_agent_writing_artifact: {
    label: "导出讲义成果",
    badge: "需授权",
    note: "导出 Markdown、LaTeX、PDF 或 ZIP。",
  },
  resume_multi_agent_writing: {
    label: "恢复讲义流程",
    badge: "需授权",
    note: "恢复失败或中断的写作流程。",
  },
  discover_feishu_resources: {
    label: "查找飞书资源",
    badge: "只读",
    note: "列出或搜索可入库的飞书资源。",
  },
  download_feishu_resource: {
    label: "下载飞书资源",
    badge: "需授权",
    note: "下载、解析并写入索引。",
  },
};

export const MCP_PROMPT_OPTIONS = [
  "teacher_handout_writer",
  "student_blank_handout_writer",
  "solution_reviewer",
] as const;

export function defaultMcpExposureSelection() {
  return {
    tools: [...MCP_TOOL_OPTIONS] as string[],
    prompts: [...MCP_PROMPT_OPTIONS] as string[],
  };
}

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
