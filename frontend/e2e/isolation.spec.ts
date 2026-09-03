import { expect, test } from "@playwright/test";
import { STUDENT_PASSWORD, loginViaUi, registerStudent, shot , navTo } from "./helpers";

/**
 * S1-可见性隔离：学生身份遍历全部页面，断言不出现答案/教师批注/内部来源标识/路径。
 * 这是把"学生版隔离"架构门禁落到 UI 层的守卫用例。
 */
test.describe("学生可见性隔离", () => {
  test("学生浏览全部页面无敏感内容泄漏", async ({ page }) => {
    test.setTimeout(300_000);
    const student = await registerStudent(undefined);
    await loginViaUi(page, student.username, STUDENT_PASSWORD);

    const forbiddenPatterns = [
      "feishu://",
      "gaokao://",
      "textbook://",
      "参考答案",
      "评分点",
      "教师批注",
      "traceId",
      "evidenceRef=",
      "/app/data/",
      "raw prompt",
    ];

    for (const nav of ["教材检索", "AI 讲题", "AI 控制台", "讲义生成", "知识库", "MCP 接入", "系统设置"]) {
      await navTo(page, nav);
      await page.waitForLoadState("networkidle").catch(() => undefined);
      const body = await page.locator("body").innerText();
      for (const pattern of forbiddenPatterns) {
        expect(body, `学生打开「${nav}」不应包含 ${pattern}`).not.toContain(pattern);
      }
      await shot(page, `isolation-${nav}`);
    }
  });
});
