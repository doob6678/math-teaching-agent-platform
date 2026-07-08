import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { TeachingTaskPanel } from "./components/TeachingTaskPanel";
import { TeachingTaskResponse } from "../shared/api/textbookApi";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/build/pdf.worker.mjs?url", () => ({
  default: "pdf-worker-test.mjs",
}));

describe("TeachingTaskPanel", () => {
  it("renders handout workflow, review entries, and math without leaking prompts", () => {
    const task: TeachingTaskResponse = {
      taskId: "task-teaching-1",
      clientRequestId: "req-teaching-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      selectedTemplate: {
        templateCode: "teacher-detail",
        displayName: "教师详解版",
        description: "教师版给答案，学生版留白。",
        category: "教师详解",
        audience: "teacher",
        difficultyBands: ["基础", "提高"],
        tags: ["答案", "板书"],
        sourceType: "skill_config",
        visualStyle: "教案式",
        referenceTitle: "教师讲义生成",
        referencePreview: "",
      },
      status: "COMPLETED",
      questionText: "已知双曲线焦距为 10，且 2a=6，求参数。",
      learningGoal: "学会双曲线定义与参数关系",
      nodes: [
        { code: "LEARNING_GOAL", name: "学习目标识别", status: "completed", summary: "识别学习目标。" },
        { code: "PUBLIC_TEXTBOOK_RETRIEVAL", name: "公开教材检索", status: "completed", summary: "命中公开教材证据 3 条。" },
        { code: "QUESTION_BANK_RETRIEVAL", name: "题库检索", status: "completed", summary: "命中题库题目 2 条。" },
        { code: "AI_DRAFT", name: "讲义内容生成", status: "completed", summary: "结构化解析成功。" },
        { code: "LATEX_HANDOUT", name: "讲义排版", status: "completed", summary: "生成教师版和学生版。" },
        { code: "HUMAN_FEEDBACK", name: "人工反馈", status: "pending", summary: "等待教师审校。" },
      ],
      reactTrace: [],
      evidence: [],
      handoutLatex: "\\section{学习目标}\n学会双曲线定义与参数关系",
      teacherHandoutLatex: "\\section{学习目标}\n学会双曲线定义与参数关系\n\\paragraph{答案与评分点} 答案为 $b^2=16$。\n\\section{16:10 横版讲解卡}\n\\paragraph{课堂投屏} 只保留例题条件和核心公式 $c^2=a^2+b^2$。\n\\begin{itemize}\n\\item 先写出 $a,c$，再求 $b^2$。\n\\end{itemize}",
      studentHandoutLatex: "\\section{学习主题}\n学会双曲线定义与参数关系\n\\section{我的解答}\n\\vspace{10em}",
      interactiveSuggestions: [],
      memoryReuse: {
        reused: false,
        memoryId: "",
        reuseScope: "",
        answer: "",
        similarity: 0,
        reason: "No reusable memory matched",
      },
      stageTimings: [
        { stage: "textbook_retrieval", elapsedMs: 1320 },
        { stage: "question_bank_retrieval", elapsedMs: 240 },
        { stage: "ai_draft", elapsedMs: 19295 },
        { stage: "handout_generation", elapsedMs: 280 },
      ],
      aiDraft: {
        enabled: true,
        providerName: "openai",
        modelCode: "gpt-5.4",
        promptTokens: 100,
        completionTokens: 50,
        totalTokens: 150,
        content: "{}",
        message: "ok",
        structured: true,
        teacherExplanation: "【知识定位】双曲线标准方程 x^2/a^2-y^2/b^2=1。【答案与评分点】答案为 $b^2=16$。",
        studentHint: "【知识速记】先写 $c^2=a^2+b^2$。【练习任务】完成参数计算。",
        knowledgePoints: ["参数关系 $c^2=a^2+b^2$"],
        followUpQuestions: ["已知焦距为 10，求 c。"],
        parseError: "",
        retryCount: 0,
        maxRetries: 1,
        recoveredAfterRetry: false,
        recoveryEvents: [],
      },
    };

    const html = renderToStaticMarkup(
      <TeachingTaskPanel
        task={task}
        loading={false}
        error=""
        history={[]}
        loadingHistory={false}
        loadingHistoryTaskId=""
        version="teacher"
        previewLatex=""
        previewPdfUrl="blob:test-pdf"
        previewPdfBytes={new Uint8Array([37, 80, 68, 70])}
        previewPdfMeta={{ bytes: new Uint8Array([37, 80, 68, 70]), renderer: "xelatex", pageCount: 4 }}
        action=""
        exportMessage=""
        feedbackRating={4}
        feedbackDecision="helpful"
        feedbackComment=""
        submittingFeedback={false}
        feedbackMessage=""
        feedbackHistory={[{
          feedbackId: "feedback-1",
          taskId: "task-teaching-1",
          tenantId: "school-a",
          subjectType: "teacher",
          subjectId: "teacher-1",
          rating: 4,
          decision: "needs_revision",
          comment: "保留结构，补充来源说明。",
          reviewContext: {
            pdfRenderer: "xelatex",
            pdfPageCount: 4,
            evidenceCount: 2,
          },
          createdAt: "2026-07-06T10:00:00Z",
        }]}
        loadingFeedbackHistory={false}
        batchFolderPath="handouts/task-teaching-1"
        onVersionChange={vi.fn()}
        onBatchFolderPathChange={vi.fn()}
        onPreviewLatex={vi.fn()}
        onPreviewPdf={vi.fn()}
        onExportLatex={vi.fn()}
        onExportPdf={vi.fn()}
        onExportBatchZip={vi.fn()}
        onSelectHistory={vi.fn()}
        onFeedbackRatingChange={vi.fn()}
        onFeedbackDecisionChange={vi.fn()}
        onFeedbackCommentChange={vi.fn()}
        onSubmitFeedback={vi.fn()}
      />,
    );

    expect(html).toContain("讲义中心");
    expect(html).toContain("下载 PDF");
    expect(html).toContain("结构审查");
    expect(html).toContain("教师版协作卡片流");
    expect(html).toContain("16:10 讲解卡");
    expect(html).toContain("16:10 讲解版");
    expect(html).toContain("课堂投屏");
    expect(html).toContain("过程对话");
    expect(html).toContain("学习目标识别");
    expect(html).toContain("公开教材检索");
    expect(html).toContain("题库检索");
    expect(html).toContain("人工反馈");
    expect(html).toContain("审校记录");
    expect(html).toContain("需要修改");
    expect(html).toContain("XeLaTeX 编译");
    expect(html).toContain("mfrac");
    expect(html).toContain("msup");
    expect(html).not.toContain("x^2/a^2-y^2/b^2=1");
    expect(html).not.toContain("MODEL_CALL_SUCCEEDED");
    expect(html).not.toContain("JSON_PARSE_SUCCEEDED");
    expect(html).not.toContain("system prompt");
    expect(html).not.toContain("PDF 版式要求");
  });
});
