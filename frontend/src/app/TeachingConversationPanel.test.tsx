import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  safeUserFacingText,
  stageDetailText,
  TeachingConversationPanel,
  TeachingConversationThreadItem,
  visibleExplanationCards,
} from "./components/TeachingConversationPanel";
import { StudentExplanationResponse, StudentExplanationStage } from "../shared/api/textbookApi";

function buildResponse(overrides: Partial<StudentExplanationResponse> = {}): StudentExplanationResponse {
  return {
    explanationId: "explain-1",
    conversationId: "conversation-1",
    conversationTitle: "反比例函数讲解",
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
        sourcePath: "教材 / 反比例函数",
        openUrl: "",
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
        conversationTitle="反比例函数讲解"
        value=""
        entries={entries}
        recentConversations={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
      imageError=""
      conversationMemoryEnabled={false}
      openingConversationId=""
      onValueChange={vi.fn()}
      onSubmit={vi.fn()}
      onImageSelect={vi.fn()}
      onClearImage={vi.fn()}
      onConversationMemoryChange={vi.fn()}
      onStartNewConversation={vi.fn()}
      onOpenConversation={vi.fn()}
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

  it("renders the backend-provided source URL so a retrieved Feishu document can be opened", () => {
    const response = buildResponse({
      sources: [{
        sourceType: "teacher_resource",
        title: "韦达/硬解定理",
        sourceUri: "teacher-resource://hard-solution-theorem/block/1",
        permissionScope: "MATH_VIP",
        snippet: "对于椭圆和双曲线，一定要记住这个硬解定理。",
        score: 0.97,
        sourcePath: "解析几何 / 韦达/硬解定理",
        openUrl: "https://my.feishu.cn/docx/AnZ3d5Qbfo9K8IxK7AecZa3unBg",
      }],
    });

    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        conversationTitle="圆锥曲线的硬解定理"
        value=""
        entries={[{ id: "assistant-source-link", role: "assistant", createdAt: "2026-07-12T08:00:00Z", response }]}
        recentConversations={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        conversationMemoryEnabled={false}
        openingConversationId=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
        onConversationMemoryChange={vi.fn()}
        onStartNewConversation={vi.fn()}
        onOpenConversation={vi.fn()}
      />,
    );

    expect(html).toContain('href="https://my.feishu.cn/docx/AnZ3d5Qbfo9K8IxK7AecZa3unBg"');
    expect(html).toContain("查看原文");
  });

  it("groups real evidence under the retrieval stage that produced it", () => {
    const response = buildResponse({
      workflowStages: [
        { stageKey: "search_textbook", title: "检索教材", status: "completed", detail: "本轮纳入 1 条教材证据。", elapsedMs: 30 },
        { stageKey: "match_knowledge_graph", title: "匹配知识点", status: "completed", detail: "本轮纳入 1 个主干知识点。", elapsedMs: 8 },
        { stageKey: "search_teacher_resources", title: "检索教师资料", status: "completed", detail: "本轮纳入 1 条教师资料。", elapsedMs: 19 },
      ],
      sources: [
        { sourceType: "textbook", title: "教材资料", sourceUri: "textbook://chapter-1", permissionScope: "PUBLIC_TEXTBOOK", snippet: "教材内容", score: 0.91, sourcePath: "教材", openUrl: "" },
        { sourceType: "knowledge_graph", title: "函数概念", sourceUri: "math-agent://knowledge/function", permissionScope: "PUBLIC", snippet: "知识点说明", score: 1, sourcePath: "函数", openUrl: "" },
        { sourceType: "teacher_resource", title: "教师笔记", sourceUri: "teacher-resource://note-1", permissionScope: "MATH_VIP", snippet: "教师资料内容", score: 0.87, sourcePath: "教师资料", openUrl: "" },
      ],
    });
    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        conversationTitle="资料分组"
        value=""
        entries={[{ id: "assistant-evidence", role: "assistant", createdAt: "2026-07-13T00:00:00Z", response }]}
        recentConversations={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        conversationMemoryEnabled={false}
        openingConversationId=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
        onConversationMemoryChange={vi.fn()}
        onStartNewConversation={vi.fn()}
        onOpenConversation={vi.fn()}
      />,
    );

    expect(html).toContain('data-retrieval-stage="search_textbook"');
    expect(html).toContain('data-retrieval-stage="match_knowledge_graph"');
    expect(html).toContain('data-retrieval-stage="search_teacher_resources"');
    expect(html).toContain("教材资料");
    expect(html).toContain("函数概念");
    expect(html).toContain("教师笔记");
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

  it("renders real streamed progress and model text without timer-driven placeholder stages", () => {
    const entries: TeachingConversationThreadItem[] = [
      {
        id: "assistant-loading",
        role: "assistant",
        createdAt: "2026-07-08T08:00:02Z",
        questionText: "这个怎么做",
        loading: true,
        liveContent: "先把等式左边因式分解，再分别令每个因式等于零。",
        liveThinking: "先确认题目是一个一元二次方程。",
        progress: {
          conversationId: "conversation-live-1",
          conversationTitle: "一元二次方程求根",
          questionText: "这个怎么做",
          imageStatus: "none",
          imageUnderstanding: buildResponse().imageUnderstanding,
          aiDraft: buildResponse().aiDraft,
          workflowStages: [{
            stageKey: "ai_compose_cards",
            title: "生成讲解",
            status: "running",
            detail: "已收到模型的实时输出。",
            elapsedMs: 620,
          }],
          cards: [{
            cardKey: "problem_understanding",
            title: "题意理解",
            summary: "要求解一个一元二次方程。",
            items: [],
            sourceUris: [],
            renderMode: "text",
          }],
          sources: [],
          totalElapsedMs: 620,
        },
      },
    ];

    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        conversationTitle="当前讲题"
        value=""
        entries={entries}
        recentConversations={[]}
        loading={true}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        conversationMemoryEnabled={false}
        openingConversationId=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
        onConversationMemoryChange={vi.fn()}
        onStartNewConversation={vi.fn()}
        onOpenConversation={vi.fn()}
      />,
    );

    expect(html).toContain("正在讲解");
    // A streamed card is model output, so its title must not be removed because it resembles an older layout.
    expect(html).toContain("题意理解");
    expect(html).toContain("先把等式左边因式分解");
    expect(html).toContain("模型思考");
    expect(html).not.toContain("读取问题");
    expect(html).not.toContain("讲义中心");
  });

  it("keeps an agent-selected mistake section neutral instead of assigning it a fixed template role", () => {
    const response = buildResponse({
      cards: [{
        cardKey: "sign_check",
        title: "常见错误",
        summary: "这里只需要提醒导数符号判断，避免把增减性方向写反。",
        items: [],
        sourceUris: [],
        renderMode: "text",
      }],
    });

    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        conversationTitle="导数符号判断"
        value=""
        entries={[{ id: "assistant-agent-section", role: "assistant", createdAt: "2026-07-12T08:00:00Z", response }]}
        recentConversations={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        conversationMemoryEnabled={false}
        openingConversationId=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
        onConversationMemoryChange={vi.fn()}
        onStartNewConversation={vi.fn()}
        onOpenConversation={vi.fn()}
      />,
    );

    expect(html).toContain("常见错误");
    expect(html).not.toContain("teaching-response-card core");
    expect(html).not.toContain("teaching-response-card mistake");
    expect(html).not.toContain("继续练习");
  });

  it("does not invent a heading when the agent returns one continuous explanation", () => {
    const response = buildResponse({
      cards: [{
        cardKey: "agent_section_1",
        title: "",
        summary: "先比较两个量的符号，再根据题设范围确定结论。",
        items: [],
        sourceUris: [],
        renderMode: "text",
      }],
    });

    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        conversationTitle="符号判断"
        value=""
        entries={[{ id: "assistant-1", role: "assistant", createdAt: "2026-07-12T00:00:00Z", response }]}
        recentConversations={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        conversationMemoryEnabled={false}
        openingConversationId=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
        onConversationMemoryChange={vi.fn()}
        onStartNewConversation={vi.fn()}
        onOpenConversation={vi.fn()}
      />,
    );

    expect(html).toContain("先比较两个量的符号");
    expect(html).not.toContain('teaching-response-card agent"><div class="teaching-response-head"');
  });

  it("keeps every agent-selected section regardless of its title or card key", () => {
    const cards = visibleExplanationCards([
      {
        cardKey: "step_by_step",
        title: "分步推理",
        summary: "正在把题目条件、命中的知识点和可用方法整理成连续讲解。",
        items: ["接下来会按老师讲题的顺序，把关键步骤一段一段补上。"],
        sourceUris: [],
        renderMode: "text",
      },
      {
        cardKey: "common_mistakes",
        title: "常见错误",
        summary: "正在结合这道题的条件和方法，整理最容易出错的地方。",
        items: ["会优先提醒最可能把题做偏的那几个点。"],
        sourceUris: [],
        renderMode: "text",
      },
      {
        cardKey: "sign_check",
        title: "分步推理",
        summary: "先判断导数的正负，再写出函数增减区间。",
        items: [],
        sourceUris: [],
        renderMode: "text",
      },
    ]);

    expect(cards).toHaveLength(3);
    expect(cards.map((card) => card.cardKey)).toEqual(["step_by_step", "common_mistakes", "sign_check"]);
  });

  it("offers a fresh conversation and keeps memory opt-in", () => {
    const html = renderToStaticMarkup(
      <TeachingConversationPanel
        conversationTitle="函数最小值"
        value=""
        entries={[]}
        recentConversations={[]}
        loading={false}
        loadingHistory={false}
        error=""
        imageDraft={null}
        uploadingImage={false}
        imageError=""
        conversationMemoryEnabled={false}
        openingConversationId=""
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
        onImageSelect={vi.fn()}
        onClearImage={vi.fn()}
        onConversationMemoryChange={vi.fn()}
        onStartNewConversation={vi.fn()}
        onOpenConversation={vi.fn()}
      />,
    );

    expect(html).toContain("新建对话");
    expect(html).toContain("关联当前会话上下文");
    expect(html).not.toContain("已启用");
  });
});
