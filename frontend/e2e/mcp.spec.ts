import { expect, test } from "@playwright/test";
import { localEnv, loginViaUi, shot, navTo } from "./helpers";

/**
 * S1-MCP：断言对象为重新部署后的前端构建。
 * 注意：密钥列表积累了大量历史 key，创建/吊销/删除必须只作用于本次新建的 key。
 */
test.describe("MCP 接入", () => {
  test("身份边界卡与工具发现可见", async ({ page }) => {
    await loginViaUi(page);
    await navTo(page, "MCP 接入");
    // "当前账号边界"是 region aria-label；边界说明在折叠 details 的 summary 上。
    await expect(page.locator('[aria-label="当前账号边界"]')).toBeVisible();
    await expect(page.getByText("凭证边界与审计")).toBeVisible();
    await expect(page.getByText(localEnv().username).first()).toBeVisible();
    // 工具发现：分层工具表（含高价值讲义协作与飞书发现）。
    await expect(page.getByText("查找飞书资源").first()).toBeVisible();
    await expect(page.getByText("会话层")).toBeVisible();
    await shot(page, "mcp-identity");
  });

  test("MCP key 创建→吊销→删除全生命周期", async ({ page }) => {
    await loginViaUi(page);
    await navTo(page, "MCP 接入");

    // 记录创建前列表第一个 key 名（列表按创建时间倒序，创建后第一行即新 key）。
    const firstKeyNameBefore = await page.locator("strong").filter({ hasText: /mcp-/ }).first().innerText();

    await page.getByRole("button", { name: "创建 MCP Key" }).click();
    await expect(page.getByText("仅此一次展示机会", { exact: false }).first()).toBeVisible({ timeout: 20_000 });
    await shot(page, "mcp-key-created");

    // 新 key 是列表第一行且名称与创建前不同。
    const firstRow = page.locator("strong").filter({ hasText: /mcp-/ }).first();
    await expect(firstRow).not.toHaveText(firstKeyNameBefore, { timeout: 20_000 });
    const newKeyName = await firstRow.innerText();
    const newRow = page.locator("li, div").filter({ hasText: newKeyName }).last();
    await newRow.getByRole("button", { name: "吊销" }).click();
    await expect(newRow.getByRole("button", { name: "删除" })).toBeVisible({ timeout: 20_000 });
    await newRow.getByRole("button", { name: "删除" }).click();
    // 物理删除后该 key 名从页面消失。
    await expect(page.getByText(newKeyName)).toHaveCount(0, { timeout: 20_000 });
    await shot(page, "mcp-key-deleted");
  });
});
