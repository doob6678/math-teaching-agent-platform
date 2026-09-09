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

  /**
   * 2026-09-08 思考乱码修复门禁：默认路由已切 glm（强制思考），思考面板必须展示连续可读的中文推理，
   * 不得出现 U+FFFD/代理对残片——worker durable 合并丢 reasoning 时前端正是这种碎片化"乱码"。
   */
  test("思考面板流式内容可读且不含替换字符", async ({ page }) => {
    test.setTimeout(240_000);
    const student = await registerStudent(undefined);
    await loginViaUi(page, student.username, STUDENT_PASSWORD);
    await navTo(page, "AI 讲题");
    const chatRegion = page.getByRole("region", { name: "AI 讲题" });
    await expect(chatRegion).toBeVisible();

    await page.getByPlaceholder("输入题目、追问或补充条件").fill(
      "已知椭圆 x^2/4 + y^2/3 = 1，求它的焦点坐标、离心率和准线方程，并说明推导过程。",
    );
    await chatRegion.locator("button.teaching-send-btn").click();

    // 展开"思考与搜索"右侧面板（主区思考行点击切换）。
    await expect(page.locator("button.teaching-thinking-row").first()).toBeVisible({ timeout: 90_000 });
    await page.locator("button.teaching-thinking-row").first().click();
    // live 思考面板与"思考过程"节同层（button 与 aside 是兄弟节点），按 aria-label 定位。
    const trace = page.locator('[aria-label="思考过程"] .teaching-thinking-trace-text').first();
    await expect(trace).toBeVisible({ timeout: 30_000 });
    // 等首段思考落地；glm 强制思考，首条 reasoning 应在 90s 内出现。
    await expect(trace).not.toHaveText("模型正在思考…", { timeout: 90_000 });

    // 观察 12s 流式增量：累计文本必须无替换符、无孤立代理对，且长度在增长（不是卡死碎片）。
    // live 行可能在采样中途被完成态替换（快回答），此时退出循环改由下方完成态断言兜底。
    let prevLength = 0;
    let grew = false;
    for (let tick = 0; tick < 6; tick++) {
      await page.waitForTimeout(2_000);
      let text = "";
      try {
        text = await trace.innerText({ timeout: 3_000 });
      } catch {
        break;
      }
      expect(text, "思考流不应包含 U+FFFD 替换字符").not.toContain("\uFFFD");
      expect(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(text),
        "思考流不应包含孤立代理项").toBe(false);
      // 碎片化检测：思考正文里应出现连续中文推理（合并丢帧时只剩 '","' 起始的 JSON 碎片）。
      expect(text, "思考正文应为连续中文推理，不应是残缺 JSON 碎片").toMatch(/[\u4e00-\u9fff]{6}/);
      if (text.length > prevLength + 4) grew = true;
      prevLength = text.length;
      // 主区思考行截尾必须按码点切：尾部不能以孤立高代理项结束（旧 slice(-60) 会切坏 emoji/生僻字）。
      try {
        const tailText = await page.locator("button.teaching-thinking-row .teaching-thinking-live-text").first().innerText();
        expect(tailText.length, "思考行尾片段应有内容").toBeGreaterThan(0);
        expect(/[\uD800-\uDBFF]$/.test(tailText), "思考行尾部不应以孤立高代理项结束").toBe(false);
      } catch {
        /* live 行已消失：本轮完成，跳出 */
        break;
      }
    }
    await shot(page, "student-chat-thinking-panel");

    // 完成态兜底断言：展开"已完成思考"折叠区，持久化思考轨迹必须连续可读（与 SSE 实时流同源校验）。
    // 长题 GLM compose 可达 1-2 分钟，先显式等待完成态头部出现再交互（不能用 actionTimeout 的 20s）。
    const doneHeader = page.locator('summary:has-text("已完成思考")').first();
    await expect(doneHeader).toBeVisible({ timeout: 150_000 });
    await doneHeader.click();
    const finalTrace = page.locator(".teaching-thinking-trace-text.static").first();
    await expect(finalTrace).toBeVisible({ timeout: 120_000 });
    const full = await finalTrace.innerText();
    expect(full.length, "完成态思考轨迹应有内容").toBeGreaterThan(50);
    expect(full, "完成态思考不应包含 U+FFFD").not.toContain("\uFFFD");
    expect(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(full),
      "完成态思考不应包含孤立代理项").toBe(false);
    expect(/[\u4e00-\u9fff]{10}/.test(full), "完成态思考应为连续中文推理").toBe(true);
    if (!grew) {
      // 流式采样窗口内已完成的轮次：至少证明完成态在增长之外仍是真实长思考。
      expect(full.length, "GLM 强制思考应有可观长度").toBeGreaterThan(150);
    }
  });
});
