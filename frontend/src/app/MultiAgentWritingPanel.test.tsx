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
  it("offers recovery only for a terminal failed workflow", () => {
    const workflow: MultiAgentWritingResponse = {
      workflowId: "workflow-failed-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      status: "FAILED",
      stages: [{
        stageCode: "resource_curation",
        agentCode: "TeacherAssistantAgent",
        traceId: "trace-resource",
        providerName: "openai",
        modelCode: "gpt-5.6-luna",
        status: "COMPLETED",
        actualUsage: { promptTokens: 100, completionTokens: 40, totalTokens: 140 },
        message: "Evidence curation completed",
        elapsedMs: 42,
      }],
      totalUsage: { promptTokens: 100, completionTokens: 40, totalTokens: 140 },
      message: "Provider unavailable at template selection",
    };

    const html = renderToStaticMarkup(
      <MultiAgentWritingPanel
        workflow={workflow}
        traces={null}
        writingGoal="teacher handout"
        questionText="space vector angle"
        providerName="openai"
        modelCode="gpt-5.6-luna"
        modelReady={true}
        starting={false}
        resuming={false}
        polling={false}
        error=""
        onWritingGoalChange={vi.fn()}
        onQuestionTextChange={vi.fn()}
        onSubmit={vi.fn()}
        onResume={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );

    expect(html).toContain("从失败点恢复");
    expect(html).toContain("只重新排队未完成阶段");
  });

  it("renders requested model, actual fallback model usage, Chinese usage labels, and diagnostics", () => {
    const workflow: MultiAgentWritingResponse = {
      workflowId: "workflow-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      status: "COMPLETED",
      stages: [
        {
          stageCode: "resource_curation",
          agentCode: "TeacherAssistantAgent",
          traceId: "trace-resource",
          providerName: "openai",
          modelCode: "gpt-5.4",
          status: "COMPLETED",
          actualUsage: { promptTokens: 100, completionTokens: 40, totalTokens: 140 },
          message: "Evidence curation completed",
          elapsedMs: 42,
        },
        {
          stageCode: "teacher_writer",
          agentCode: "CoursewareAgent",
          traceId: "trace-teacher",
          providerName: "dashscope",
          modelCode: "qwen3.6-flash",
          status: "COMPLETED",
          actualUsage: { promptTokens: 80, completionTokens: 30, totalTokens: 110 },
          message: "Teacher version completed",
          elapsedMs: 137,
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
          traceId: "trace-teacher",
          planId: "workflow-1:teacher_writer",
          createdAt: "2026-07-01T04:20:00Z",
          tenantId: "school-a",
          subjectType: "teacher",
          subjectId: "teacher-1",
          agentCode: "CoursewareAgent",
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
    expect(html).toContain("资料汇总");
    expect(html).toContain("教师版");
    expect(html).toContain("学生版");
    expect(html).toContain("16:10 讲解版");
    expect(html).toContain("合并结果");
    expect(html).toContain("耗时 137 ms");
    expect(html).toContain("OpenAI / gpt-5.4");
    expect(html).toContain("来源审查");
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
