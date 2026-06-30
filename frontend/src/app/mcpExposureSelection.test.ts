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
    ]);
    expect(enabledAgain).toEqual([...MCP_TOOL_OPTIONS]);
  });

  it("keeps protected tools visible as locked metadata but not selectable by default", () => {
    expect([...MCP_PROTECTED_TOOL_OPTIONS]).toEqual([
      "plan_agent_run",
      "create_teaching_task",
      "export_handout_pdf",
      "list_teacher_resources",
    ]);
    expect(MCP_TOOL_OPTION_META.search_teacher_resource_evidence.badge).toBe("read-only");
    expect(MCP_TOOL_OPTION_META.get_teaching_ai_trace.note).toBe("Owned task diagnostics.");
    expect(MCP_TOOL_OPTION_META.get_ai_diagnostic_summary.note).toBe("Retry and fallback summary.");
    expect(MCP_TOOL_OPTION_META.export_handout_pdf.badge).toBe("protected");
  });
});
