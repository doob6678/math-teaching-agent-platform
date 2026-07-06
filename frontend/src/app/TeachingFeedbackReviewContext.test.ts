import { describe, expect, it, vi } from "vitest";
import { buildTeachingFeedbackReviewContext } from "./App";
import { TeachingTaskResponse } from "../shared/api/textbookApi";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/build/pdf.worker.mjs?url", () => ({
  default: "pdf-worker-test.mjs",
}));

describe("buildTeachingFeedbackReviewContext", () => {
  it("captures PDF preview evidence and handout safety checks for human review", () => {
    const task: TeachingTaskResponse = {
      taskId: "task-review-1",
      clientRequestId: "req-review-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      selectedTemplate: {
        templateCode: "teacher_blackboard_solution_v1",
        displayName: "教师详解版",
        sourceType: "builtin",
        audience: "teacher",
        description: "教师版给答案和讲评。",
      },
      status: "COMPLETED",
      questionText: "已知双曲线焦距为 $10$，且 $2a=6$，求 $a,c,b^2$。",
      learningGoal: "双曲线定义与参数关系",
      nodes: [],
      reactTrace: [],
      evidence: [
        {
          sourceScope: "PUBLIC_TEXTBOOK",
          sourceTitle: "人教B版选择性必修一 / 2.6 双曲线及其方程",
          chunkId: "chunk-1",
          pageNo: 154,
          snippet: "双曲线定义与参数关系。",
        },
        {
          sourceScope: "QUESTION_BANK",
          sourceTitle: "双曲线参数基础题 / 难度：A 基础",
          chunkId: "question-1",
          pageNo: 0,
          snippet: "已知双曲线焦距为 $10$，且 $2a=6$。",
        },
      ],
      handoutLatex: "",
      teacherHandoutLatex: "",
      studentHandoutLatex: "",
      interactiveSuggestions: [],
    };
    const latex = `
      \\section{讲义信息}
      \\section{来源索引}
      \\section{板书流程}
      \\section{例题与答案}
      \\section{课堂追问}
      \\section{课后订正记录}
      \\paragraph{答案与评分点}由 $c^2=a^2+b^2$ 得 $b^2=16$。
    `;

    const context = buildTeachingFeedbackReviewContext(
      task,
      "teacher",
      latex,
      { bytes: new Uint8Array([37, 80, 68, 70]), renderer: "xelatex", pageCount: 4 },
      "task-review-1:teacher",
    );

    expect(context.schemaVersion).toBe("teaching-feedback-review-v2");
    expect(context.pdfPreviewReady).toBe(true);
    expect(context.pdfRendererIsXeLaTeX).toBe(true);
    expect(context.evidenceScopes).toEqual(["PUBLIC_TEXTBOOK", "QUESTION_BANK"]);
    expect(context.checks.coreColumnCoverage).toBe("6/6");
    expect(context.checks.internalDebugLeak).toBe(false);
    expect(context.checks.layoutRuleLeak).toBe(false);
    expect(context.checks.teacherAnswerPresent).toBe(true);
    expect(context.reviewEvidence.pdfPreview).toMatchObject({
      artifactType: "pdf_preview",
      version: "teacher",
      previewReady: true,
      versionBound: true,
      renderer: "xelatex",
      pageCount: 4,
    });
    expect(context.reviewEvidence.safety).toMatchObject({
      internalDebugLeak: false,
      layoutRuleLeak: false,
      answerLeak: true,
      studentAnswerIsolated: true,
      teacherAnswerPresent: true,
    });
    expect(context.aiReviewBrief).toContain("PDF：xelatex / 4页");
  });
});
