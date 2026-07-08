import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  safeUserFacingText,
  stageDetailText,
  TeachingConversationPanel,
  TeachingConversationThreadItem,
} from "./components/TeachingConversationPanel";
import { StudentExplanationResponse, StudentExplanationStage } from "../shared/api/textbookApi";

function buildResponse(overrides: Partial<StudentExplanationResponse> = {}): StudentExplanationResponse {
  return {
    explanationId: "explain-1",
    conversationId: "conversation-1",
    tenantId: "school-a",
    studentId: "student-001",
    viewerRole: "student",
    questionText: "反比例函数 $y=k/x$ 的图像性质。",
    imageStatus: "none",
    imageUnderstanding: {
      enabled: false,
      succeeded: false,
      providerName: "",
      modelCode: "",
      problemText: "",
      confidence: 0,
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      message: "",
    },
    generatedBy: "student-explanation-orchestrator",
    aiDraft: {
      enabled: true,
      providerName: "dashscope",
      modelCode: "qwen",
      promptTokens: 10,
      completionTokens: 20,
      totalTokens: 30,
      structured: true,
      message: "ok",
      recoveryEvents: [],
    },
    workflowStages: [
      {
        stageKey: "ai_compose_cards",
        title: "MODEL_CALL_SUCCEEDED openai/gpt-5.5",
        status: "completed",
        detail: "Used real model output and parsed it as JSON explanation cards. tokens=1759",
        elapsedMs: 1200,
      },
    ],
    cards: [
      {
        cardKey: "core",
        title: "核心思路",
        summary: "MODEL_CALL_SUCCEEDED openai/gpt-5.5\n先把反比例函数写成 $y=k/x$，图像由 $k$ 的符号决定。",
        items: [
          "promptTokens=100",
          "标准形式渲染为 $y=\\frac{k}{x}$，不要显示成普通斜杠。",
        ],
        sourceUris: ["textbook://p1"],
        renderMode: "formula",
      },
    ],
    sources: [
      {
        sourceType: "textbook",
        title: "PUBLIC_TEXTBOOK",
        sourceUri: "textbook://p1",
        permissionScope: "public",
        snippet: "JSON_PARSE_SUCCEEDED\n反比例函数 $y=\\frac{k}{x}$。",
        score: 0.92,
      },
    ],
    totalElapsedMs: 3000,
    ...overrides,
  };
}

describe("TeachingConversationPanel", () => {
  it("renders AI question chat as formula cards without exposing internal model events", () => {
    const entries: TeachingConversationThreadItem[] = [
      {
        id: "user-1",
        role: "user",
        questionText: "讲一下反比例函数",
        createdAt: "2026-07-08T08:00:00Z",
      },
      {
        id: "assistant-1",
        role: "assistant",
        createdAt: "2026-07-08T08:00:02Z",
        response: buildResponse(),
      },
    ];

    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        value=""
        entries={entries}
        history={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
      />,
    );

    expect(html).toContain("AI 讲题");
    expect(html).toContain("上传图片");
    expect(html).toContain("粘贴图片");
    expect(html).toContain("核心思路");
    expect(html).toContain("katex");
    expect(html).toContain("mfrac");
    expect(html).not.toContain("MODEL_CALL");
    expect(html).not.toContain("JSON_PARSE");
    expect(html).not.toContain("promptTokens");
    expect(html).not.toContain("tokens=1759");
    expect(html).not.toContain("讲义中心");
    expect(html).not.toContain("下载 PDF");
  });

  it("translates workflow details and hides raw JSON or token diagnostics", () => {
    const stage: StudentExplanationStage = {
      stageKey: "ai_compose_cards",
      title: "MODEL_CALL_SUCCEEDED",
      status: "completed",
      detail: "Used real model output and parsed it as JSON explanation cards. tokens=1759",
      elapsedMs: 300,
    };

    expect(stageDetailText(stage)).toBe("已用模型结果整理成讲解卡片。");
    expect(safeUserFacingText('{"model":"gpt","tokens":123,"cards":[]}')).toBe("内容已整理。");
  });
});
