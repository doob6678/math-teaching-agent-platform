import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { AgentTracePanel } from "./App";
import {
  AgentTraceDiagnosticSummaryResponse,
  AgentTraceResponse,
  AgentTraceUsageSummaryResponse,
} from "../shared/api/textbookApi";

describe("AgentTracePanel", () => {
  it("renders recoverable traces as a Chinese conversation process timeline", () => {
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
        diagnosticEvents: [
          {
            eventType: "JSON_PARSE_SUCCEEDED",
            providerName: "openai",
            modelCode: "gpt-5.4",
            attemptNo: 0,
            retryable: false,
            message: "Structured teaching draft parsed.",
          },
        ],
      },
    ];
    const usageSummary: AgentTraceUsageSummaryResponse = {
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      agentCode: "CoursewareAgent",
      status: "COMPLETED",
      runCount: 1,
      totalUsage: { promptTokens: 123, completionTokens: 45, totalTokens: 168 },
      modelUsages: [
        {
          providerName: "openai",
          modelCode: "gpt-5.4",
          runCount: 1,
          promptTokens: 123,
          completionTokens: 45,
          totalTokens: 168,
        },
      ],
    };
    const diagnosticSummary: AgentTraceDiagnosticSummaryResponse = {
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      agentCode: "CoursewareAgent",
      status: "COMPLETED",
      runCount: 1,
      diagnosticEventCount: 3,
      jsonParseFailureCount: 1,
      retryScheduledCount: 1,
      retryRecoveredCount: 1,
      providerRotationCount: 0,
      modelCallFailureCount: 0,
      modelDiagnostics: [
        {
          providerName: "openai",
          modelCode: "gpt-5.4",
          runCount: 1,
          diagnosticEventCount: 3,
          jsonParseFailureCount: 1,
          retryScheduledCount: 1,
          retryRecoveredCount: 1,
          providerRotationCount: 0,
          modelCallFailureCount: 0,
          totalTokens: 168,
        },
      ],
    };

    const html = renderToStaticMarkup(
      <AgentTracePanel
        traces={traces}
        usageSummary={usageSummary}
        diagnosticSummary={diagnosticSummary}
        loading={false}
        error=""
        onRefresh={vi.fn()}
      />,
    );

    expect(html).toContain("过程流");
    expect(html).toContain("AI 运行、工具调用与恢复记录");
    expect(html).toContain("讲义生成");
    expect(html).toContain("OpenAI");
    expect(html).toContain("gpt-5.4");
    expect(html).toContain("已完成");
    expect(html).toContain("用量 168");
    expect(html).toContain("明确运行边界");
    expect(html).toContain("准备工具调用");
    expect(html).toContain("查找并绑定证据");
    expect(html).toContain("执行阶段记录");
    expect(html).toContain("结构化解析完成");
    expect(html).toContain("生成可恢复记录");
    expect(html).toContain("模型调用成功，已记录服务商返回的用量。");
    expect(html).toContain("重试、解析和模型切换统计");
    expect(html).toContain("JSON 解析失败 1 次");
    expect(html).toContain("重试恢复 1 次");
    expect(html).toContain("公开教材");
    expect(html).not.toContain("tokens");
    expect(html).not.toContain("Model health");
  });
});
