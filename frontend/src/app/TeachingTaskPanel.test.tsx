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
  it("renders teaching workflow nodes as grouped Chinese conversation steps", () => {
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
        { code: "HUMAN_FEEDBACK", name: "人类反馈", status: "pending", summary: "等待教师审校。" },
      ],
      reactTrace: [],
      evidence: [],
      handoutLatex: "\\section{学习目标}\n学会双曲线定义与参数关系",
      teacherHandoutLatex: "\\section{讲义模板与版式}\nPDF 版式要求：页眉展示主题和版本，页脚展示页码，教师版使用讲评色。\n\\section{学习目标}\n学会双曲线定义与参数关系\n\\paragraph{知识定位}双曲线\n\\paragraph{答案与评分点}答案为 $b^2=16$。\n参数关系 c\\textasciicircum{}2=a\\textasciicircum{}2+b\\textasciicircum{}2。",
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
        teacherExplanation: "【知识定位】双曲线标准方程 x²/a²-y²/b²=1。 【方法步骤】设解析式 y=\\frac{k}{x}，代入后得到 y=-\\frac{6}{x}，继续联立一次函数并写出 $y=-\\frac{1}{2}x+3$ 后比较交点。 【答案与评分点】答案为 $b^2=16$。",
        studentHint: "【知识速记】先写 $c^2=a^2+b^2$。 【练习任务】完成参数计算。",
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
            schemaVersion: "teaching-feedback-review-v2",
            handoutVersion: "teacher",
            pdfRenderer: "xelatex",
            pdfPageCount: 4,
            pdfPreviewReady: true,
            evidenceCount: 2,
            sourceTraceable: true,
            aiReviewBrief: [
              "版本：教师版",
              "模板：教师详解版",
              "结构：5/6 核心栏目",
              "PDF：xelatex / 4页",
              "安全：未发现内部词泄漏",
            ],
            reviewEvidence: {
              safety: {
                internalDebugLeak: false,
                layoutRuleLeak: false,
                answerLeak: false,
                studentAnswerIsolated: true,
                teacherAnswerPresent: true,
              },
              pdfPreview: {
                visualEvidence: {
                  artifactType: "browser_pdf_canvas",
                  captured: true,
                  selector: ".pdf-page-canvas",
                  imageRef: "teaching-task:task-teaching-1:teacher:pdf-page:1",
                  attachToAiReview: true,
                },
              },
            },
            checks: {
              matchedCoreColumns: 5,
              coreColumnTotal: 6,
              hasMath: true,
              hasWorkspace: true,
              answerLeak: false,
              internalDebugLeak: false,
              layoutRuleLeak: false,
              teacherAnswerPresent: true,
              pdfVisualEvidenceCaptured: true,
            },
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

    expect(html).toContain("过程对话");
    expect(html).toContain("像对话一样展示检索、生成、排版和审校");
    expect(html).toContain("已确定讲义框架");
    expect(html).toContain("工具调用");
    expect(html).toContain("工具调用与检索");
    expect(html).toContain("公开教材检索");
    expect(html).toContain("题库检索");
    expect(html).toContain("1.3 秒");
    expect(html).toContain("240 ms");
    expect(html).toContain("内容生成与排版");
    expect(html).toContain("讲义内容生成");
    expect(html).toContain("讲义排版");
    expect(html).toContain("19 秒");
    expect(html).toContain("审查与交付");
    expect(html).toContain("人类反馈");
    expect(html).toContain("讲义草稿已准备好");
    expect(html).toContain("讲义结构摘要");
    expect(html).toContain("mfrac");
    expect(html).toContain("msup");
    expect(html).toContain("正在渲染首页");
    expect(html).not.toContain("textasciicircum");
    expect(html).toContain("教师版");
    expect(html).toContain("学生版");
    expect(html).toContain("知识定位");
    expect(html).toContain("PDF 预览");
    expect(html).toContain("PDF 真实渲染预览");
    expect(html).toContain("class=\"pdf-page-canvas\"");
    expect(html).toContain("data-preview-state=\"loading\"");
    expect(html).toContain("data-page-count=\"4\"");
    expect(html).toContain("讲义 PDF 页面预览");
    expect(html).toContain("结构栏目");
    expect(html).toContain("公式渲染");
    expect(html).toContain("教师版内容");
    expect(html).toContain("调试词");
    expect(html).toContain("版式词");
    expect(html).toContain("未发现内部词泄漏");
    expect(html).toContain("未发现版式规则泄漏");
    expect(html).toContain("来源追溯");
    expect(html).toContain("缺少教材/题库来源");
    expect(html).toContain("审校记录");
    expect(html).toContain("需要修改");
    expect(html).toContain("PDF：XeLaTeX 编译 · 4 页");
    expect(html).toContain("PDF：xelatex / 4页");
    expect(html).toContain("PDF已预览");
    expect(html).toContain("预览图证据已记录");
    expect(html).toContain("来源：2 条");
    expect(html).toContain("结构：5/6 栏");
    expect(html).toContain("结构：5/6 核心栏目");
    expect(html).toContain("安全：未发现内部词泄漏");
    expect(html).toContain("无调试词泄漏");
    expect(html).toContain("无版式规则泄漏");
    expect(html).not.toContain("PDF 版式要求");
    expect(html).not.toContain("页眉展示主题");
    expect(html).not.toContain("打印版式完整");
    expect(html).not.toContain("版式无重叠");
    expect(html).not.toContain("$y=-");
    expect(html).not.toContain("【知识定位】");
    expect(html).not.toContain("【练习任务】");
    expect(html).not.toContain("ParseError");
    expect(html).not.toContain("MODEL_CALL_SUCCEEDED");
    expect(html).not.toContain("tokens");
  });
});
