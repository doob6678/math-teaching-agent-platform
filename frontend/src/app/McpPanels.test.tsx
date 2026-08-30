import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { McpIdentityBoundaryCard } from "./components/McpPanels";

describe("McpPanels", () => {
  it("shows backend-bound identity and sandbox isolation rules for MCP clients", () => {
    const html = renderToStaticMarkup(
      <McpIdentityBoundaryCard
        username="teacher"
        userId="teacher-001"
        roleLabel="教师"
        tenantId="school-a"
      />,
    );

    expect(html).toContain("账号");
    expect(html).toContain("teacher-001");
    expect(html).toContain("school-a");
    expect(html).toContain("前端不传身份参数");
    expect(html).toContain("凭证边界与审计");
    expect(html).toContain("租户隔离");
    expect(html).toContain("身份绑定");
    expect(html).toContain("最小暴露");
    expect(html).toContain("审计回溯");
  });
});
