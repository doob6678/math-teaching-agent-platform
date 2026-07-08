import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { MultiAgentWritingPanel } from "./components/MultiAgentWritingPanel";
import { MultiAgentWritingResponse, MultiAgentWritingTraceResponse } from "../shared/api/textbookApi";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/build/pdf.worker.mjs?url", () => ({
  default: "pdf-worker-test.mjs",
}));

describe("MultiAgentWritingPanel", () => {
  it("renders requested model, actual fallback model usage, Chinese usage labels, and diagnostics", () => {
    const workflow: MultiAgentWritingResponse = {
      workflowId: "workflow-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      status: "COMPLETED",
      stages: [
        {
          stageCode: "draft",
          agentCode: "CoursewareAgent",
          traceId: "trace-draft",
          providerName: "openai",
          modelCode: "gpt-5.4",
          status: "COMPLETED",
          actualUsage: { promptTokens: 100, completionTokens: 40, totalTokens: 140 },
          message: "Draft completed",
        },
        {
          stageCode: "review",
          agentCode: "CoursewareReviewer",
          traceId: "trace-review",
          providerName: "dashscope",
          modelCode: "qwen3.6-flash",
          status: "COMPLETED",
          actualUsage: { promptTokens: 80, completionTokens: 30, totalTokens: 110 },
          message: "Fallback review completed",
        },
      ],
      totalUsage: { promptTokens: 180, completionTokens: 70, totalTokens: 250 },
      message: "Workflow completed",
    };
    const traces: MultiAgentWritingTraceResponse = {
      workflowId: "workflow-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      stageCount: 2,
      totalUsage: { promptTokens: 180, completionTokens: 70, totalTokens: 250 },
      stages: [
        {
          traceId: "trace-review",
          planId: "workflow-1:review",
          createdAt: "2026-07-01T04:20:00Z",
          tenantId: "school-a",
          subjectType: "teacher",
          subjectId: "teacher-1",
          agentCode: "CoursewareReviewer",
          providerName: "dashscope",
          modelCode: "qwen3.6-flash",
          status: "COMPLETED",
          estimatedCost: 0.1,
          allowedToolScopes: ["tool:courseware:generate"],
          allowedDataScopes: ["TEACHER_PRIVATE"],
          evidenceRefs: ["feishu:docx-1"],
          stageTimings: [{ stage: "model_call", elapsedMs: 12 }],
          actualUsage: { promptTokens: 80, completionTokens: 30, totalTokens: 110 },
          message: "Live model response recorded with provider usage metadata.",
          diagnosticEvents: [
            {
              eventType: "PROVIDER_ROTATED",
              providerName: "dashscope",
              modelCode: "qwen3.6-flash",
              attemptNo: 1,
              retryable: true,
              message: "Switching to next enabled provider after model call failure.",
            },
          ],
        },
      ],
    };

    const html = renderToStaticMarkup(
      <MultiAgentWritingPanel
        workflow={workflow}
        traces={traces}
        writingGoal="teacher handout"
        questionText="space vector angle"
        providerName="openai"
        modelCode="gpt-5.4"
        modelReady={true}
        starting={false}
        resuming={false}
        polling={false}
        pdfPreviewUrl="blob:multi-agent-pdf"
        pdfPreviewBytes={new Uint8Array([37, 80, 68, 70])}
        error=""
        onWritingGoalChange={vi.fn()}
        onQuestionTextChange={vi.fn()}
        onSubmit={vi.fn()}
        onResume={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );

    expect(html).toContain("讲义已生成");
    expect(html).toContain("实际模型");
    expect(html).toContain("通义千问 / qwen3.6-flash");
    expect(html).toContain("讲义初稿");
    expect(html).toContain("OpenAI / gpt-5.4");
    expect(html).toContain("质量审校");
    expect(html).toContain("通义千问 / qwen3.6-flash");
    expect(html).not.toContain("tokens");
    expect(html).toContain("建议先预览 PDF");
    expect(html).toContain("协作讲义 PDF 预览");
    expect(html).toContain("class=\"pdf-page-canvas\"");
    expect(html).toContain("aria-label=\"上一页\"");
    expect(html).toContain("aria-label=\"下一页\"");
    expect(html).toContain("aria-label=\"跳转到页码\"");
    expect(html).toContain("aria-label=\"最后一页\"");
    expect(html).not.toContain("<iframe");
    expect(html).toContain("成果文件");
    expect(html).toContain("打开成果");
    expect(html).toContain("执行追踪 1 条");
  });
});
