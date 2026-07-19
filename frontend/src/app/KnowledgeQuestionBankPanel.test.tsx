import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { KnowledgeQuestionBankPanel } from "./components/KnowledgeQuestionBankPanel";

describe("KnowledgeQuestionBankPanel", () => {
  it("renders readable relation and question summaries without raw backend fragments", () => {
    const html = renderToStaticMarkup(
      <KnowledgeQuestionBankPanel
        knowledgePoints={[
          {
            tenantId: "school-a",
            knowledgePointId: "point-function",
            knowledgePointName: "函数基础",
            chapterPath: "高中数学/函数",
            permissionScope: "MATH_VIP",
            sourceSummary: "主干图谱",
            ownerSubjectId: "teacher-1",
            status: "active",
          },
          {
            tenantId: "school-a",
            knowledgePointId: "point-derivative",
            knowledgePointName: "导数研究函数",
            chapterPath: "高中数学/导数",
            permissionScope: "MATH_VIP",
            sourceSummary: "主干图谱",
            ownerSubjectId: "teacher-1",
            status: "active",
          },
        ]}
        knowledgeRelations={[
          {
            tenantId: "school-a",
            relationId: "rel-1",
            sourceKnowledgePointId: "point-function",
            targetKnowledgePointId: "point-derivative",
            relationType: "PREREQUISITE_FOR",
            evidenceSummary: "函数性质支撑导数研究函数。",
            status: "active",
          },
        ]}
        questions={[
          {
            questionId: "q-1",
            questionTitle: "赵礼显数学 ***",
            questionText: "赵礼显数学 *** 1. 已知函数 f(x) 在区间上单调，求参数范围并说明理由。",
            answerJson: "{}",
            difficulty: "medium",
            permissionScope: "MATH_VIP",
            knowledgePointIds: ["point-function"],
            status: "active",
          },
        ]}
        knowledgePointName=""
        chapterPath=""
        questionTitle=""
        questionText=""
        query=""
        saving={false}
        loadingQuestions={false}
        error=""
        questionPage={1}
        questionPageSize={10}
        onKnowledgePointNameChange={vi.fn()}
        onChapterPathChange={vi.fn()}
        onQuestionTitleChange={vi.fn()}
        onQuestionTextChange={vi.fn()}
        onQueryChange={vi.fn()}
        onQuestionPageChange={vi.fn()}
        onQuestionPageSizeChange={vi.fn()}
        onCreateKnowledgePoint={vi.fn()}
        onCreateQuestion={vi.fn()}
        onSearchQuestions={vi.fn()}
      />,
    );

    expect(html).toContain("前置关系");
    expect(html).toContain("函数基础 → 导数研究函数");
    expect(html).toContain("已知函数 f(x)");
    expect(html).not.toContain("PREREQUISITE_FOR");
    expect(html).not.toContain("赵礼显数学");
    expect(html).not.toContain("***");
  });
});
