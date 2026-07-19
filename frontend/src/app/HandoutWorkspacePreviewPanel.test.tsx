import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { HandoutWorkspacePreviewPanel } from "./components/HandoutWorkspacePreviewPanel";
import { TeachingTaskResponse } from "../shared/api/textbookApi";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/build/pdf.worker.mjs?url", () => ({
  default: "pdf-worker-test.mjs",
}));

function buildTask(overrides: Partial<TeachingTaskResponse> = {}): TeachingTaskResponse {
  return {
    taskId: "task-handout-review",
    clientRequestId: "req-handout-review",
    tenantId: "school-a",
    subjectType: "teacher",
    subjectId: "teacher-1",
    selectedTemplate: {
      templateCode: "teacher-detail",
      displayName: "教师详解版",
      sourceType: "skill_config",
      audience: "teacher",
      description: "教师版给答案，学生版留白。",
      category: "教师详解",
      visualStyle: "教案式",
      difficultyBands: ["基础", "提高"],
      tags: ["答案", "板书"],
    },
    status: "COMPLETED",
    questionText: "已知反比例函数图像上一点，求 k。",
    learningGoal: "反比例函数基础题型",
    nodes: [],
    reactTrace: [],
    evidence: [],
    handoutLatex: "\\section{反比例函数基础题型}",
    teacherHandoutLatex: "\\section{反比例函数基础题型}\\paragraph{答案} $y=\\frac{k}{x}$",
    studentHandoutLatex: "\\section{反比例函数基础题型}\\section{连续编号练习}\\vspace{6em}",
    lectureHandoutLatex: "\\section{16:10 讲解卡}\\paragraph{课堂投屏} 反比例函数 $y=\\frac{k}{x}$。\\vspace{10em}",
    interactiveSuggestions: [],
    memoryReuse: {
      reused: false,
      memoryId: "",
      reuseScope: "",
      answer: "",
      similarity: 0,
      reason: "No reusable memory matched",
    },
    stageTimings: [],
    aiDraft: {
      enabled: true,
      providerName: "deepseek",
      modelCode: "deepseek-v4-flash",
      promptTokens: 100,
      completionTokens: 80,
      totalTokens: 180,
      content: "{}",
      message: "ok",
      structured: true,
      teacherExplanation: "【知识定位】反比例函数 $y=\\frac{k}{x}$。",
      studentHint: "【知识速记】先判断 k 的符号。",
      knowledgePoints: ["反比例函数 $y=\\frac{k}{x}$"],
      followUpQuestions: ["判断点是否在图像上。"],
      parseError: "",
      retryCount: 0,
      maxRetries: 1,
      recoveredAfterRetry: false,
      recoveryEvents: [],
    },
    ...overrides,
  };
}

function renderPanel(task: TeachingTaskResponse, version: "teacher" | "student" | "lecture" = "student") {
  return renderToStaticMarkup(
    <HandoutWorkspacePreviewPanel
      task={task}
      version={version}
      previewLatex=""
      previewTaskKey=""
      previewPdfUrl=""
      previewPdfBytes={null}
      previewPdfMeta={null}
      previewPdfTaskKey=""
      action=""
      exportMessage=""
      previewError=""
      feedbackRating={4}
      feedbackDecision="needs_revision"
      feedbackComment="学生版第 2 题增加作答空间。"
      submittingFeedback={false}
      feedbackMessage="反馈已记录：需要修改 / 4 星"
      feedbackHistory={[{
        feedbackId: "feedback-1",
        taskId: "task-handout-review",
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        rating: 4,
        decision: "needs_revision",
        comment: "学生版补留白。",
        createdAt: "2026-07-08T08:00:00Z",
      }]}
      loadingFeedbackHistory={false}
      onVersionChange={vi.fn()}
        onPreviewPdf={vi.fn()}
      onPreviewLatex={vi.fn()}
      onResumeTask={vi.fn()}
        onExportPdf={vi.fn()}
        onSaveHandoutVersion={vi.fn()}
        onFeedbackRatingChange={vi.fn()}
      onFeedbackDecisionChange={vi.fn()}
      onFeedbackCommentChange={vi.fn()}
      onSubmitFeedback={vi.fn()}
    />,
  );
}

describe("HandoutWorkspacePreviewPanel", () => {
  it("renders the preview workspace in readable Chinese without exposing internal text", () => {
    const html = renderPanel(buildTask(), "student");

    expect(html).toContain("当前讲义");
    expect(html).toContain("反比例函数基础题型");
    expect(html).toContain("校对结论");
    expect(html).toContain("未返回");
    expect(html).toContain("当前任务还没有结构化校对摘要");
    expect(html).toContain("学生版");
    expect(html).not.toContain("MODEL_CALL");
    expect(html).not.toContain("JSON_PARSE");
    expect(html).not.toContain("？？？");
  });

  it("keeps lecture preview available for legacy tasks without stored lecture latex", () => {
    const html = renderPanel(buildTask({ lectureHandoutLatex: undefined }), "teacher");

    expect(html).toContain("16:10");
  });

  it("warns when a student handout draft leaks answer-like content", () => {
    const task = buildTask({
      studentHandoutLatex: "\\section{学生练习}\\paragraph{参考答案} $k=2$",
    });

    const html = renderPanel(task, "student");

    expect(html).toContain("学生版疑似含答案");
  });

  it("supports lecture handout review without labeling the blank workspace", () => {
    const html = renderPanel(buildTask(), "lecture");

    expect(html).toContain("16:10 讲解版");
    expect(html).toContain("适合投屏讲解");
    expect(html).not.toContain("留白区");
    expect(html).not.toContain("教师手写区");
    expect(html).not.toContain("手写区");
    expect(html).not.toContain("板书留白");
  });

  it("shows durable failure progress and a continue action", () => {
    const html = renderPanel(buildTask({
      status: "FAILED",
      errorMessage: "PDF 预览请求过于频繁",
      nodes: [{ code: "PUBLIC_TEXTBOOK_RETRIEVAL", name: "公开教材检索", status: "completed", summary: "命中公开教材证据 2 条" }],
      evidence: [{ sourceScope: "PUBLIC_TEXTBOOK", sourceTitle: "教材A", pageNo: 101, chunkId: "chunk-1", snippet: "函数定义" }],
      stageTimings: [{ stage: "textbook_retrieval", elapsedMs: 1200 }],
    }), "teacher");

    expect(html).toContain("生成失败");
    expect(html).toContain("PDF 预览请求过于频繁");
    expect(html).toContain("继续生成");
    expect(html).toContain("已记录 1 个阶段耗时");
  });
});
