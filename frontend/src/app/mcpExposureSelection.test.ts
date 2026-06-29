import { describe, expect, it } from "vitest";
import {
  MCP_PROMPT_OPTIONS,
  MCP_TOOL_OPTIONS,
  defaultMcpExposureSelection,
  toggleMcpExposureOption,
} from "./mcpExposureSelection";

describe("mcpExposureSelection", () => {
  it("selects every exposed MCP tool and prompt by default", () => {
    const selection = defaultMcpExposureSelection();

    expect(selection.tools).toEqual([...MCP_TOOL_OPTIONS]);
    expect(selection.prompts).toEqual([...MCP_PROMPT_OPTIONS]);
  });

  it("keeps deterministic ordering when manually disabling and enabling options", () => {
    const disabled = toggleMcpExposureOption(
      [...MCP_TOOL_OPTIONS],
      "export_handout_pdf",
      false,
      MCP_TOOL_OPTIONS,
    );
    const enabledAgain = toggleMcpExposureOption(disabled, "export_handout_pdf", true, MCP_TOOL_OPTIONS);

    expect(disabled).toEqual([
      "search_textbook_evidence",
      "plan_agent_run",
      "create_teaching_task",
      "list_teacher_resources",
    ]);
    expect(enabledAgain).toEqual([...MCP_TOOL_OPTIONS]);
  });
});
