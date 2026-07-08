import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { StudentDashboardPanel } from "./components/StudentDashboardPanel";
import { StudentDashboardResponse } from "../shared/api/textbookApi";

describe("StudentDashboardPanel", () => {
  it("renders a paginated Chinese student dashboard without raw graph internals", () => {
    const dashboard: StudentDashboardResponse = {
      tenantId: "school-a",
      studentId: "student-001",
      subjectRole: "student",
      viewerRole: "admin",
      viewerSubjectId: "admin-001",
      isAdminView: true,
      knowledgeProgress: Array.from({ length: 12 }, (_, index) => ({
        knowledgePointId: `math-point-${index + 1}`,
        knowledgePointName: `知识点 ${index + 1}`,
        textbookAnchor: `教材页 ${index + 1}`,
        feishuDocUrl: "",
        progressPercent: 50 + index,
      })),
      weakPoints: Array.from({ length: 7 }, (_, index) => ({
        knowledgePointId: `weak-${index + 1}`,
        knowledgePointName: `薄弱点 ${index + 1}`,
        weaknessLevel: index + 1,
        evidenceSummary: "真实学习记录",
      })),
      recentQuestions: Array.from({ length: 7 }, (_, index) => ({
        recordId: `record-${index + 1}`,
        sourceType: "student_memory",
        questionTitle: `历史问题 ${index + 1}`,
        knowledgePointName: `知识点 ${index + 1}`,
        status: "active",
      })),
      scoreTrend: Array.from({ length: 7 }, (_, index) => ({
        examName: `考试 ${index + 1}`,
        score: 90 + index,
        rankInGrade: 20 - index,
        extractedWeakPointCount: index,
      })),
      resourceScopes: [{ scopeCode: "PUBLIC_TEXTBOOK", scopeName: "Public textbook" }],
      knowledgeGraph: {
        generatedFrom: "dashboard_progress+weak_points+textbook_anchor+feishu_anchor",
        nodes: [
          {
            knowledgePointId: "math-vector-dot-product",
            knowledgePointName: "space vector dot product",
            chapterPath: "Selective compulsory / space vector / page 35",
            masteryPercent: 68,
            riskLevel: "medium",
            evidenceLinks: [
              {
                sourceType: "textbook",
                title: "Selective compulsory / space vector / page 35",
                url: "/api/textbooks/search?query=math-vector-dot-product",
                permissionScope: "PUBLIC_TEXTBOOK",
              },
            ],
          },
        ],
        edges: [
          {
            edgeId: "edge-vector-dot-solid-geometry",
            sourceKnowledgePointId: "math-vector-dot-product",
            targetKnowledgePointId: "math-solid-geometry",
            relationType: "PREREQUISITE_FOR",
            evidenceSummary: "dot product supports angle and perpendicularity judgments",
          },
        ],
      },
    };

    const html = renderToStaticMarkup(
      <StudentDashboardPanel
        dashboard={dashboard}
        loading={false}
        error=""
        viewerRole="admin"
        targetStudentId="student-001"
        onRefresh={() => undefined}
        onLoad={() => undefined}
      />,
    );

    expect(html).toContain("查看学生画像");
    expect(html).toContain("管理员查看");
    expect(html).toContain("刷新快照");
    expect(html).toContain("student-001");
    expect(html).toContain("查看学生");
    expect(html).toContain("知识点 1");
    expect(html).toContain("第 1 / 2 页");
    expect(html).toContain("共 12 条知识点");
    expect(html).toContain("每页");
    expect(html).toContain(">10<");
    expect(html).toContain("学习记录 / 进行中");
    expect(html).not.toContain("student_memory / active");
    expect(html).not.toContain("Knowledge Graph");
    expect(html).not.toContain("PREREQUISITE_FOR");
    expect(html).not.toContain("dashboard_progress");
  });

  it("does not render a global dashboard when admin has not selected a student", () => {
    const html = renderToStaticMarkup(
      <StudentDashboardPanel
        dashboard={null}
        loading={false}
        error=""
        viewerRole="admin"
        targetStudentId=""
        onRefresh={() => undefined}
        onLoad={() => undefined}
      />,
    );

    expect(html).toContain("请输入要查看的学生 ID");
    expect(html).not.toContain("全局概览");
    expect(html).not.toContain("__all_students__");
  });
});
