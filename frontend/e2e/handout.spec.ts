import { expect, test } from "@playwright/test";
import { localEnv, loginViaUi, shot , navTo } from "./helpers";

/**
 * S1-讲义生成全生命周期：填表 → 异步提交 → 轮询至 COMPLETED → 协作卡片流/预览可见。
 * 真实模型三 Writer 分钟级任务，超时上限 25 分钟；这是 S1 的核心"全功能"用例。
 */
test.describe("讲义生成", () => {
  test("讲义任务从提交走到完成并出现三版本产物", async ({ page }) => {
    test.setTimeout(1_500_000);
    await loginViaUi(page);
    await navTo(page, "讲义生成");
    const workbench = page.locator('[aria-label="讲义工作台"]');
    await expect(workbench).toBeVisible();

    const goal = workbench.getByPlaceholder("例如：双曲线专题讲评 / 反比例函数练习");
    await goal.fill("一元二次方程配方法专题讲义");
    const question = workbench.getByPlaceholder("例如：已知 f(x)=x^2-4x+3，求其在 [0,3] 上的最小值。");
    if ((await question.count()) > 0) {
      await question.fill("解方程 x^2-6x+8=0，写出配方全过程。");
    }
    await shot(page, "handout-form-filled");

    await workbench.getByRole("button", { name: /开始生成|生成中/ }).click();
    // 提交被接受：进入生成中状态或出现协作条目。
    await expect(workbench.getByText(/生成中|已提交|队列|进度|CREATED|RUNNING/).first()).toBeVisible({ timeout: 60_000 });
    await shot(page, "handout-submitted");

    // 轮询 UI 至终态：完成标记（COMPLETED/已完成/预览可下载）或失败标记。
    await expect
      .poll(
        async () => {
          const body = await page.locator("body").innerText();
          if (/COMPLETED|已完成|生成完成/.test(body)) return "completed";
          if (/FAILED|失败/.test(body)) return "failed";
          return "running";
        },
        { timeout: 1_440_000, intervals: [15_000, 30_000] },
      )
      .toBe("completed");
    await shot(page, "handout-completed");

    // 三版本产物清单可见（aria-label 精确定位，避免与版本 chip 重名冲突）。
    await expect(page.locator('[aria-label="三个讲义版本"]')).toBeVisible();
    await shot(page, "handout-three-versions");
  });
});
