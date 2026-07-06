import { describe, expect, it } from "vitest";
import {
  MCP_PROMPT_OPTIONS,
  MCP_PROTECTED_TOOL_OPTIONS,
  MCP_TOOL_OPTION_META,
  MCP_TOOL_OPTIONS,
  defaultMcpExposureSelection,
  toggleMcpExposureOption,
} from "./mcpExposureSelection";

describe("mcpExposureSelection", () => {
  it("selects every exposed MCP tool and prompt by default", () => {
    const selection = defaultMcpExposureSelection();

    expect(selection.tools).toEqual([...MCP_TOOL_OPTIONS]);
    expect(selection.prompts).toEqual([...MCP_PROMPT_OPTIONS]);
    expect(selection.tools).toEqual([
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
    ]);
    expect(selection.tools).not.toContain("create_teaching_task");
    expect(selection.tools).not.toContain("export_handout_pdf");
  });

  it("keeps deterministic ordering when manually disabling and enabling options", () => {
    const disabled = toggleMcpExposureOption(
      [...MCP_TOOL_OPTIONS],
      "search_textbook_evidence",
      false,
      MCP_TOOL_OPTIONS,
    );
    const enabledAgain = toggleMcpExposureOption(disabled, "search_textbook_evidence", true, MCP_TOOL_OPTIONS);

    expect(disabled).toEqual([
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
    ]);
    expect(enabledAgain).toEqual([...MCP_TOOL_OPTIONS]);
  });

  it("does not advertise future MCP tools without backend execution endpoints", () => {
    expect([...MCP_PROTECTED_TOOL_OPTIONS]).toEqual([]);
    expect(MCP_TOOL_OPTION_META.search_teacher_resource_evidence.badge).toBe("只读");
    expect(MCP_TOOL_OPTION_META.get_teaching_ai_trace.note).toBe("查看本人可见教学任务的模型调用与解析记录。");
    expect(MCP_TOOL_OPTION_META.get_ai_diagnostic_summary.note).toBe("查看重试、降级和故障恢复汇总。");
    expect(MCP_TOOL_OPTION_META.download_feishu_resource.badge).toBe("需授权");
    expect(MCP_TOOL_OPTIONS).not.toContain("create_teaching_task");
    expect(MCP_TOOL_OPTIONS).not.toContain("export_handout_pdf");
    expect(MCP_TOOL_OPTIONS).not.toContain("list_teacher_resources");
  });
});
