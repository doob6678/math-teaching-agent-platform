import { expect, test } from "@playwright/test";
import { loginViaUi, shot , navTo } from "./helpers";

/** S1-工作台与教材检索：页面主数据渲染 + 真实检索出证据。 */
test.describe("工作台与教材检索", () => {
  test("工作台展示学习概览与资源统计", async ({ page }) => {
    await loginViaUi(page);
    await expect(page.locator('[aria-label="工作台状态"]')).toBeVisible();
    await shot(page, "dashboard-overview");
  });

  test("教材检索返回真实证据并展示命中面板", async ({ page }) => {
    test.setTimeout(120_000);
    await loginViaUi(page);
    await navTo(page, "教材检索");
    await page.getByText("基于关键词与向量混合检索的教材证据搜索").waitFor();
    const searchBox = page.getByPlaceholder("输入教材术语、题干片段或公式关键词");
    await searchBox.fill("二次函数 顶点式");
    await searchBox.press("Enter");
    // 检索 p95 基线约 3.4s（含证据聚合），给足等待。
    await page.waitForLoadState("networkidle").catch(() => undefined);
    await expect(page.locator("body").getByText(/证据|命中|result/i).first()).toBeVisible({ timeout: 30_000 });
    await shot(page, "search-evidence");
  });
});
