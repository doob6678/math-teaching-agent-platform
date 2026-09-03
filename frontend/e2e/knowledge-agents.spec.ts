import { expect, test } from "@playwright/test";
import { loginViaUi, shot , navTo } from "./helpers";

/** S1-知识库与 AI 控制台：图谱 SVG 渲染 + 模型目录健康。 */
test.describe("知识库与 AI 控制台", () => {
  test("知识图谱 SVG 渲染出节点且支持交互区可见", async ({ page }) => {
    test.setTimeout(120_000);
    await loginViaUi(page);
    await navTo(page, "知识库");
    await page.waitForLoadState("networkidle").catch(() => undefined);
    const svg = page.locator("svg").first();
    await expect(svg).toBeVisible({ timeout: 30_000 });
    await expect(page.locator("svg circle, svg rect").first()).toBeVisible();
    await shot(page, "knowledge-graph");
  });

  test("AI 控制台展示模型目录与健康状态", async ({ page }) => {
    await loginViaUi(page);
    await navTo(page, "AI 控制台");
    await expect(page.getByText("管理模型选择、真实调用、讲义生成能力和执行记录")).toBeVisible();
    await page.waitForLoadState("networkidle").catch(() => undefined);
    await expect(page.locator("body").getByText(/模型|健康/).first()).toBeVisible({ timeout: 30_000 });
    await shot(page, "agents-console");
  });
});
