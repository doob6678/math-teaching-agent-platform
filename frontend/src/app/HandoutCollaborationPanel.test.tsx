import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { HandoutCollaborationPanel, HandoutCollaborationThreadItem } from "./components/HandoutCollaborationPanel";
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
      currentTaskId={task.taskId}
      version="lecture"
      entries={entries ?? [{
        id: "assistant-1",
        role: "assistant",
        createdAt: "2026-07-08T10:00:00Z",
        task,
      }]}
      history={[task]}
      loading={false}
      loadingHistory={false}
      error=""
      onLearningGoalChange={vi.fn()}
      onQuestionTextChange={vi.fn()}
      onEvidenceLimitChange={vi.fn()}
      onSubmit={vi.fn()}
      onSelectHistory={vi.fn()}
      onPreviewPdf={vi.fn()}
      onPreviewLatex={vi.fn()}
      onExportPdf={vi.fn()}
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

  it("renders readable Chinese copy and hides corrupted history items", () => {
    const goodTask = buildTask();
    const badTask = buildTask({
      taskId: "bad-task",
      learningGoal: "？？？？",
      teacherHandoutLatex: "MODEL_CALL_SUCCEEDED JSON_PARSE tokens=100",
    });
    const html = renderPanel(goodTask, []);
    const htmlWithHistory = renderToStaticMarkup(
      <HandoutCollaborationPanel
        learningGoal=""
        questionText=""
        evidenceLimit={5}
        selectedTemplateName="教师详解版"
        currentTaskId=""
        version="teacher"
        entries={[]}
        history={[badTask, goodTask]}
        loading={false}
        loadingHistory={false}
        error=""
        onLearningGoalChange={vi.fn()}
        onQuestionTextChange={vi.fn()}
        onEvidenceLimitChange={vi.fn()}
        onSubmit={vi.fn()}
        onSelectHistory={vi.fn()}
        onPreviewPdf={vi.fn()}
        onPreviewLatex={vi.fn()}
        onExportPdf={vi.fn()}
      />,
    );

    expect(html).toContain("讲义协作");
    expect(html).toContain("输入主题后开始生成讲义");
    expect(htmlWithHistory).toContain("最近讲义");
    expect(htmlWithHistory).toContain("双曲线专题");
    expect(htmlWithHistory).not.toContain("？？？？");
    expect(htmlWithHistory).not.toContain("MODEL_CALL");
  });
});
