import { expect, test } from "@playwright/test";
import { loginViaUi, localEnv, shot } from "./helpers";

/** S1-认证：登录失败/成功/登出主干。 */
test.describe("认证与登录", () => {
  test("错误密码显示明确错误且不进入工作台", async ({ page }) => {
    await page.goto("/");
    const inputs = page.getByPlaceholder("输入后端账号");
    if ((await inputs.count()) === 0) {
      await page.getByRole("button", { name: "前往登录" }).first().click();
      await inputs.waitFor();
    }
    await inputs.fill(localEnv().username);
    await page.getByPlaceholder("输入真实密码").fill("wrong-password-000");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page.getByText(/Invalid username or password|用户名或密码|登录失败/).first()).toBeVisible({ timeout: 20_000 });
    await shot(page, "auth-wrong-password");
    await expect(page.getByText("已登录为", { exact: false })).toHaveCount(0);
  });

  test("正确密码登录进入工作台并可退出", async ({ page }) => {
    await loginViaUi(page);
    await shot(page, "auth-login-dashboard");
    // 侧栏 8 个导航项齐备（页面标题即导航 label）。
    for (const label of ["工作台", "教材检索", "AI 讲题", "AI 控制台", "讲义生成", "知识库", "MCP 接入", "系统设置"]) {
      await expect(page.getByRole("button", { name: new RegExp(label) }).first()).toBeVisible();
    }
    // 退出登录回到登录态之外（头像按钮类名 nav-avatar）。
    await page.locator("button.nav-avatar").click();
    await page.getByRole("button", { name: "退出登录" }).click();
    await expect(page.getByText("欢迎使用 Math Agent").or(page.getByText("欢迎登录"))).toBeVisible();
    await shot(page, "auth-logout");
  });
});
