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
      lectureHandoutLatex: "\\section{16:10 横版讲解卡}\\paragraph{课堂投屏} 反比例函数 $y=\\frac{k}{x}$。\\vspace{10em}",
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

describe("HandoutWorkspacePreviewPanel", () => {
  it("renders the handout review checkpoint in the new handout workspace", () => {
    const task = buildTask();
    const html = renderToStaticMarkup(
      <HandoutWorkspacePreviewPanel
        task={task}
        version="student"
        previewLatex=""
        previewTaskKey=""
        previewPdfUrl=""
        previewPdfBytes={null}
        previewPdfMeta={null}
        previewPdfTaskKey=""
        action=""
        exportMessage=""
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
        onExportPdf={vi.fn()}
        onFeedbackRatingChange={vi.fn()}
        onFeedbackDecisionChange={vi.fn()}
        onFeedbackCommentChange={vi.fn()}
        onSubmitFeedback={vi.fn()}
      />,
    );

    expect(html).toContain("人工审查");
    expect(html).toContain("核对学生版是否只保留题目、提示和作答空间");
    expect(html).toContain("真实 PDF");
    expect(html).toContain("结构审查");
    expect(html).toContain("版本隔离");
    expect(html).toContain("当前无明显泄露");
    expect(html).toContain("提交审查");
    expect(html).toContain("审查记录");
    expect(html).toContain("学生版补留白。");
    expect(html).not.toContain("MODEL_CALL");
    expect(html).not.toContain("JSON_PARSE");
  });

  it("keeps lecture preview available for legacy tasks without stored lecture latex", () => {
    const task = buildTask({ lectureHandoutLatex: undefined });

    const html = renderToStaticMarkup(
      <HandoutWorkspacePreviewPanel
        task={task}
        version="teacher"
        previewLatex=""
        previewTaskKey=""
        previewPdfUrl=""
        previewPdfBytes={null}
        previewPdfMeta={null}
        previewPdfTaskKey=""
        action=""
        exportMessage=""
        feedbackRating={4}
        feedbackDecision="helpful"
        feedbackComment=""
        submittingFeedback={false}
        feedbackMessage=""
        feedbackHistory={[]}
        loadingFeedbackHistory={false}
        onVersionChange={vi.fn()}
        onPreviewPdf={vi.fn()}
        onPreviewLatex={vi.fn()}
        onExportPdf={vi.fn()}
        onFeedbackRatingChange={vi.fn()}
        onFeedbackDecisionChange={vi.fn()}
        onFeedbackCommentChange={vi.fn()}
        onSubmitFeedback={vi.fn()}
      />,
    );

    expect(html).toContain("横版讲解");
  });

  it("warns when a student handout draft leaks answer-like content", () => {
    const task = buildTask({
      studentHandoutLatex: "\\section{学生练习}\\paragraph{参考答案} $k=2$",
    });

    const html = renderToStaticMarkup(
      <HandoutWorkspacePreviewPanel
        task={task}
        version="student"
        previewLatex=""
        previewTaskKey=""
        previewPdfUrl=""
        previewPdfBytes={null}
        previewPdfMeta={null}
        previewPdfTaskKey=""
        action=""
        exportMessage=""
        feedbackRating={4}
        feedbackDecision="needs_revision"
        feedbackComment=""
        submittingFeedback={false}
        feedbackMessage=""
        feedbackHistory={[]}
        loadingFeedbackHistory={false}
        onVersionChange={vi.fn()}
        onPreviewPdf={vi.fn()}
        onPreviewLatex={vi.fn()}
        onExportPdf={vi.fn()}
        onFeedbackRatingChange={vi.fn()}
        onFeedbackDecisionChange={vi.fn()}
        onFeedbackCommentChange={vi.fn()}
        onSubmitFeedback={vi.fn()}
      />,
    );

    expect(html).toContain("学生版疑似含答案");
  });

  it("supports lecture handout review without labeling the blank workspace", () => {
    const task = buildTask();

    const html = renderToStaticMarkup(
      <HandoutWorkspacePreviewPanel
        task={task}
        version="lecture"
        previewLatex=""
        previewTaskKey=""
        previewPdfUrl=""
        previewPdfBytes={null}
        previewPdfMeta={null}
        previewPdfTaskKey=""
        action=""
        exportMessage=""
        feedbackRating={4}
        feedbackDecision="helpful"
        feedbackComment=""
        submittingFeedback={false}
        feedbackMessage=""
        feedbackHistory={[]}
        loadingFeedbackHistory={false}
        onVersionChange={vi.fn()}
        onPreviewPdf={vi.fn()}
        onPreviewLatex={vi.fn()}
        onExportPdf={vi.fn()}
        onFeedbackRatingChange={vi.fn()}
        onFeedbackDecisionChange={vi.fn()}
        onFeedbackCommentChange={vi.fn()}
        onSubmitFeedback={vi.fn()}
      />,
    );

    expect(html).toContain("横版讲解");
    expect(html).toContain("核对横版讲解卡是否适合投屏");
    expect(html).not.toContain("留白区");
    expect(html).not.toContain("教师手写区");
    expect(html).not.toContain("手写区");
    expect(html).not.toContain("板书留白");
  });
});
