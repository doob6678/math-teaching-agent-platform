import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { StudentDashboardPanel } from "./App";
import { StudentDashboardResponse } from "../shared/api/textbookApi";

describe("StudentDashboardPanel", () => {
  it("renders knowledge graph nodes, edges, mastery, and evidence links", () => {
    const dashboard: StudentDashboardResponse = {
      tenantId: "school-a",
      studentId: "student-1",
      viewerRole: "student",
      viewerSubjectId: "student-1",
      isAdminView: false,
      knowledgeProgress: [
        {
          knowledgePointId: "math-vector-dot-product",
          knowledgePointName: "space vector dot product",
          textbookAnchor: "Selective compulsory / space vector / page 35",
          feishuDocUrl: "https://my.feishu.cn/docx/vector",
          progressPercent: 68,
        },
      ],
      weakPoints: [],
      recentQuestions: [],
      scoreTrend: [],
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
              {
                sourceType: "feishu",
                title: "space vector dot product",
                url: "https://my.feishu.cn/docx/vector",
                permissionScope: "MATH_VIP",
              },
            ],
          },
          {
            knowledgePointId: "math-solid-geometry",
            knowledgePointName: "solid geometry relation",
            chapterPath: "Compulsory / solid geometry / page 74",
            masteryPercent: 54,
            riskLevel: "high",
            evidenceLinks: [],
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
      <StudentDashboardPanel dashboard={dashboard} loading={false} error="" onRefresh={() => undefined} />,
    );

    expect(html).toContain("Knowledge Graph");
    expect(html).toContain("Refresh snapshot");
    expect(html).toContain("space vector dot product");
    expect(html).toContain("68%");
    expect(html).toContain("medium");
    expect(html).toContain("PREREQUISITE_FOR");
    expect(html).toContain("textbook");
    expect(html).toContain("feishu");
    expect(html).toContain("dashboard_progress");
  });
});
