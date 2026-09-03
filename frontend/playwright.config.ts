import { defineConfig, devices } from "@playwright/test";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 企业级 UI E2E 门禁（S1 阶段产物，见 测试与功能梳理/企业级测试设计/剩余测试执行计划-2026-08-30.md）。
 * 证据目录与 run-manifest 约定：每次运行在 S1-ui 证据目录下留截图、trace 与 json 报告。
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const evidenceDir = path.resolve(here, "../测试与功能梳理/测试报告/2026-08-30-第一轮全量/S1-ui");
mkdirSync(evidenceDir, { recursive: true });

export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: true,
  workers: 2,
  retries: 0,
  reporter: [
    ["list"],
    ["json", { outputFile: path.join(evidenceDir, "playwright-report.json") }],
  ],
  outputDir: path.join(evidenceDir, "test-results"),
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://127.0.0.1:5173",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    actionTimeout: 20_000,
    navigationTimeout: 30_000,
    ...devices["Desktop Chrome"],
  },
});
