import { request as playwrightRequest, type Page, type APIRequestContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * E2E 公共助手：凭据从仓库根 .env 读取（git 忽略，不入库），
 * 截图统一落 S1-ui 证据目录并编号，保证验收可回放。
 */

export const HERE = path.dirname(fileURLToPath(import.meta.url));
export const EVIDENCE_DIR = path.resolve(HERE, "../../测试与功能梳理/测试报告/2026-08-30-第一轮全量/S1-ui");

interface LocalEnv {
  username: string;
  password: string;
  backendBaseUrl: string;
}

let cachedEnv: LocalEnv | null = null;

/** 从仓库根 .env 解析验收账号与后端地址；缺失时给出可操作错误。 */
export function localEnv(): LocalEnv {
  if (cachedEnv) return cachedEnv;
  const envPath = path.resolve(HERE, "../../.env");
  const raw = readFileSync(envPath, "utf-8");
  const pairs = new Map<string, string>();
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq > 0) pairs.set(trimmed.slice(0, eq).trim(), trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, ""));
  }
  const username = pairs.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME") ?? "";
  const password = pairs.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD") ?? "";
  if (!username || !password) {
    throw new Error(".env 缺少 MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME/PASSWORD，无法执行 UI E2E");
  }
  cachedEnv = {
    username,
    password,
    backendBaseUrl: process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:8080",
  };
  return cachedEnv;
}

let shotSeq = 0;

/** 关键步骤截图：NN-名称.png，落 S1-ui 证据目录。 */
export async function shot(page: Page, name: string): Promise<string> {
  shotSeq += 1;
  const file = path.join(EVIDENCE_DIR, `${String(shotSeq).padStart(2, "0")}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  return file;
}

let testSeq = 0;

/** 为隔离/压测生成稳定可复现的测试账号名（秒级+随机后缀，避免同分钟重跑撞名）。 */
export function nextStudentUsername(): string {
  testSeq += 1;
  const stamp = new Date().toISOString().slice(5, 19).replace(/[-T:]/g, "");
  const rand = Math.random().toString(36).slice(2, 6);
  return `e2e-s-${stamp}-${rand}${String(testSeq).padStart(2, "0")}`;
}

export const STUDENT_PASSWORD = "e2e-student-pass-001";

/** 通过后端注册接口创建学生测试账号（公开注册端点，返回登录态 cookie）。 */
export async function registerStudent(
  base: APIRequestContext | undefined,
  backendBaseUrl = localEnv().backendBaseUrl,
): Promise<{ username: string; password: string }> {
  const ctx = base ?? (await playwrightRequest.newContext());
  const username = nextStudentUsername();
  const response = await ctx.post(`${backendBaseUrl}/api/auth/register`, {
    data: { username, password: STUDENT_PASSWORD },
  });
  if (response.status() !== 200) {
    throw new Error(`注册学生账号失败: ${response.status()} ${await response.text()}`);
  }
  return { username, password: STUDENT_PASSWORD };
}

/** UI 登录：应用为状态导航（无路由），默认落工作台；经登录引导卡或侧栏进入登录表单。 */
export async function loginViaUi(page: Page, username?: string, password?: string): Promise<void> {
  const env = localEnv();
  await page.goto("/");
  const loginInputs = page.getByPlaceholder("输入后端账号");
  if ((await loginInputs.count()) === 0) {
    await page.getByRole("button", { name: "前往登录" }).first().click();
    await loginInputs.waitFor({ timeout: 20_000 });
  }
  await loginInputs.fill(username ?? env.username);
  await page.getByPlaceholder("输入真实密码").fill(password ?? env.password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  // 登录成功后应用立刻跳工作台；头像 aria-label 是后端 userId（UUID，非用户名），
  // 稳定信号是工作台概览区（aria-label="工作台状态"）。
  await page.locator('[aria-label="工作台状态"]').waitFor({ timeout: 30_000 });
}

/** 侧栏导航：用 nav-link 类精确定位，避免与工作台快捷卡片同名冲突。 */
export async function navTo(page: Page, label: string): Promise<void> {
  await page.locator(`button.nav-link[aria-label="${label}"]`).click();
}
