import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { AgentTracePanel } from "./App";
import { AgentTraceResponse } from "../shared/api/textbookApi";

describe("AgentTracePanel", () => {
  it("renders recoverable traces with backend resolved model and evidence", () => {
    const traces: AgentTraceResponse[] = [
      {
        traceId: "trace-1",
        planId: "plan-1",
        createdAt: "2026-06-29T05:00:00Z",
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        agentCode: "CoursewareAgent",
        providerName: "openai",
        modelCode: "gpt-5.4",
        status: "COMPLETED",
        estimatedCost: 0.46,
        allowedToolScopes: ["tool:courseware:generate", "tool:search:textbook"],
        allowedDataScopes: ["TEACHER_PRIVATE", "PUBLIC_TEXTBOOK"],
        evidenceRefs: ["teaching-task:task-1", "textbook:chapter-1"],
        stageTimings: [{ stage: "model_call", elapsedMs: 14 }],
        actualUsage: { promptTokens: 123, completionTokens: 45, totalTokens: 168 },
        message: "Live model response recorded with provider usage metadata.",
      },
    ];

    const html = renderToStaticMarkup(
      <AgentTracePanel traces={traces} loading={false} error="" onRefresh={vi.fn()} />,
    );

    expect(html).toContain("trace-1");
    expect(html).toContain("CoursewareAgent");
    expect(html).toContain("openai");
    expect(html).toContain("gpt-5.4");
    expect(html).toContain("COMPLETED");
    expect(html).toContain("168");
    expect(html).toContain("model_call");
    expect(html).toContain("Live model response recorded");
    expect(html).toContain("teaching-task:task-1");
    expect(html).toContain("tool:search:textbook");
  });
});
