import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  buildWorkflowNodeInspection,
  HandoutCollaborationPanel,
  HandoutCollaborationThreadItem,
} from "./components/HandoutCollaborationPanel";
import { TeachingTaskResponse } from "../shared/api/textbookApi";

function buildTask(overrides: Partial<TeachingTaskResponse> = {}): TeachingTaskResponse {
  return {
    taskId: "task-lecture-version",
    clientRequestId: "client-lecture-version",
    tenantId: "school-a",
    subjectType: "teacher",
    subjectId: "teacher-001",
    selectedTemplate: {
      templateCode: "teacher-detail",
      displayName: "教师详解版",
      description: "教师版给答案，学生版留练习。",
      category: "教师详解",
      audience: "teacher",
      difficultyBands: ["基础"],
      tags: ["讲义"],
      sourceType: "skill_config",
      visualStyle: "教案式",
      referenceTitle: "讲义模板",
      referencePreview: "",
    },
    status: "COMPLETED",
    questionText: "讲解双曲线标准方程。",
    learningGoal: "双曲线专题",
    nodes: [{ code: "LATEX_HANDOUT", name: "讲义排版", status: "completed", summary: "生成 16:10 讲解稿。" }],
    reactTrace: [],
    evidence: [{
      sourceScope: "PUBLIC_TEXTBOOK",
      sourceTitle: "人教 B 版选择性必修一 / 双曲线",
      chunkId: "chunk-1",
      pageNo: 152,
      snippet: "双曲线标准方程。",
    }],
    handoutLatex: "\\section{教师版} 双曲线。",
    teacherHandoutLatex: "\\section{教师版} 双曲线。",
    studentHandoutLatex: "\\section{学生版} 完成练习。",
    lectureHandoutLatex: "\\section{16:10 讲解卡}\\paragraph{课堂投屏} $c^2=a^2+b^2$。",
    interactiveSuggestions: [],
    stageTimings: [{ stage: "latex_handout", elapsedMs: 1200 }],
    aiDraft: {
      enabled: true,
      providerName: "dashscope",
      modelCode: "qwen",
      promptTokens: 10,
      completionTokens: 20,
      totalTokens: 30,
      content: "{}",
      message: "ok",
      structured: true,
      teacherExplanation: "【讲评主线】先回到定义。",
      studentHint: "【学习提示】先写参数关系。",
      knowledgePoints: ["参数关系"],
      followUpQuestions: ["给出 $2a$ 和 $2c$，求 $b^2$。"],
      parseError: "",
      retryCount: 0,
      maxRetries: 1,
      recoveredAfterRetry: false,
      recoveryEvents: [],
    },
    ...overrides,
  };
}

function renderPanel(task = buildTask(), entries?: HandoutCollaborationThreadItem[]) {
  return renderToStaticMarkup(
    <HandoutCollaborationPanel
      learningGoal="双曲线专题"
      questionText=""
      evidenceLimit={5}
      selectedTemplateName="教师详解版"
      version="lecture"
      entries={entries ?? [{
        id: "assistant-1",
        role: "assistant",
        createdAt: "2026-07-08T10:00:00Z",
        task,
      }]}
      loading={false}
      error=""
      onLearningGoalChange={vi.fn()}
      onQuestionTextChange={vi.fn()}
      onEvidenceLimitChange={vi.fn()}
      onSubmit={vi.fn()}
        onPreviewPdf={vi.fn()}
        onPreviewLatex={vi.fn()}
        onExportPdf={vi.fn()}
        onVersionChange={vi.fn()}
      />,
  );
}

describe("HandoutCollaborationPanel", () => {
  it("shows lecture handout as the lecture version instead of student version", () => {
    const html = renderPanel();

    expect(html).toContain("16:10 讲解版");
    expect(html).toContain("当前版本");
    expect(html).not.toContain("当前版本</span><strong>学生版");
  });

  it("renders readable Chinese copy without exposing internal prompt/debug text", () => {
    const goodTask = buildTask();
    const html = renderPanel(goodTask, []);

    expect(html).toContain("讲义工作台");
    expect(html).toContain("输入主题后开始");
    expect(html).toContain("双曲线专题");
    expect(html).not.toContain("MODEL_CALL");
    expect(html).not.toContain("JSON_PARSE");
    expect(html).not.toContain("tokens=100");
  });

  it("opens a retrieval node through an inspectable control and keeps its evidence scope exact", () => {
    const task = buildTask({
      nodes: [{ code: "QUESTION_BANK_RETRIEVAL", name: "题库检索", status: "completed", summary: "命中题库题目 1 条。" }],
      workflowEvents: [{
        eventId: "evidence",
        sourceType: "tool",
        sourceName: "EvidenceCollector",
        eventType: "evidence",
        status: "completed",
        title: "并行收集资料",
        summary: "题库实际命中 1 条。",
        artifactRefs: ["QUESTION_BANK"],
      }],
      evidence: [
        {
          sourceScope: "QUESTION_BANK",
          sourceTitle: "空间向量例题 3",
          chunkId: "question-3",
          pageNo: 0,
          snippet: "在四棱锥中求线面角，先建立空间直角坐标系。",
        },
        {
          sourceScope: "TEACHER_RESOURCE",
          sourceTitle: "教师私有拓展",
          chunkId: "teacher-1",
          pageNo: 12,
          snippet: "这条资料不属于题库检索节点。",
        },
      ],
    });

    const inspection = buildWorkflowNodeInspection(task, "QUESTION_BANK_RETRIEVAL");
    const html = renderPanel(task);

    expect(html).toContain("查看节点详情");
    expect(html).toContain('aria-expanded="false"');
    expect(inspection?.events).toEqual([expect.objectContaining({ title: "并行收集资料", summary: "题库实际命中 1 条。" })]);
    expect(inspection?.evidence).toEqual([expect.objectContaining({ sourceTitle: "空间向量例题 3", chunkId: "question-3" })]);
    expect(inspection?.evidence.some((item) => item.sourceScope === "TEACHER_RESOURCE")).toBe(false);
  });

  it("keeps the complete authorized workflow record available to the inspector", () => {
    const fullRecord = `已找到资料：${"原始资料内容".repeat(100)}\n下一步：逐题核对答案。`;
    const task = buildTask({
      nodes: [{ code: "QUESTION_BANK_RETRIEVAL", name: "题库检索", status: "completed", summary: "已完成。" }],
      workflowEvents: [{
        eventId: "evidence",
        sourceType: "tool",
        sourceName: "EvidenceCollector",
        eventType: "evidence",
        status: "completed",
        title: "并行收集资料",
        summary: fullRecord,
        artifactRefs: ["QUESTION_BANK"],
      }],
    });

    const inspection = buildWorkflowNodeInspection(task, "QUESTION_BANK_RETRIEVAL");

    expect(inspection?.events[0]?.summary).toContain("下一步：逐题核对答案。");
    expect(inspection?.events[0]?.summary.length).toBeGreaterThan(360);
  });
});
