import { expect, test } from "@playwright/test";
import { STUDENT_PASSWORD, loginViaUi, registerStudent, shot, navTo } from "./helpers";

/**
 * S1-AI 讲题：真实学生注册 → 登录 → 提交题目 → 流式首字到达。
 * TTFT 基线约 4.1s，负载下放宽到 90s 门禁；正文由真实模型生成，只断言流式行为与内容可见。
 */
test.describe("AI 讲题（学生）", () => {
  test("学生讲题流式返回且不泄漏教师内部标识", async ({ page }) => {
    test.setTimeout(240_000);
    const student = await registerStudent(undefined);
    await loginViaUi(page, student.username, STUDENT_PASSWORD);
    await navTo(page, "AI 讲题");
    // 页面区域与侧栏按钮同名，用 region 角色精确定位。
    const chatRegion = page.getByRole("region", { name: "AI 讲题" });
    await expect(chatRegion).toBeVisible();

    const input = page.getByPlaceholder("输入题目、追问或补充条件");
    await input.fill("已知函数 f(x)=x^2-4x+3，求它在区间 [0,3] 上的最小值，并说明为什么。");
    // 输入框不响应 Enter，提交走表单的发送按钮（唯一 .teaching-send-btn）。
    await chatRegion.locator("button.teaching-send-btn").click();
    await shot(page, "student-chat-submitted");

    // 流式对话出现且逐步有内容；首字到达门禁 90s。
    await expect(chatRegion.getByText(/最小值|函数|配方|顶点/).first()).toBeVisible({ timeout: 90_000 });
    // 后端真实处理过程（检索/模型节点事件）面板出现，证明 AI 响应链路真实执行。
    await expect(page.locator('[aria-label="真实处理过程"]')).toBeVisible({ timeout: 90_000 });
    await page.waitForTimeout(8_000); // 给流式正文一点累积窗口再截图
    await shot(page, "student-chat-streaming");

    // 隔离断言：讲题界面不出现内部来源标识与教师批注字样。
    const body = await page.locator("body").innerText();
    for (const forbidden of ["feishu://", "gaokao://", "textbook://", "教师批注", "evidenceRef="]) {
      expect(body, `学生讲题界面不应包含 ${forbidden}`).not.toContain(forbidden);
    }
  });
});
