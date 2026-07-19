/**
 * Real-stack handout acceptance runner.
 *
 * The script deliberately uses the authenticated backend APIs instead of mocked
 * fixtures.  Each request receives a unique idempotency key, while capability
 * tokens are acquired for the exact request hash consumed by the protected API.
 * The resulting JSON, PDFs, extracted text, and page PNGs are durable evidence
 * for the acceptance document and make a later failure reproducible.
 */
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const root = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const outputRoot = resolve(process.env.ACCEPTANCE_OUTPUT_DIR ?? join(root, "output", "acceptance", "2026-07-13-agent-acceptance_matrix"));
const backend = process.env.ACCEPTANCE_BACKEND_URL ?? "http://127.0.0.1:8080";
const username = process.env.ACCEPTANCE_USERNAME ?? "teacher";
const password = process.env.ACCEPTANCE_PASSWORD ?? "teacher-123456";
const pollIntervalMs = Number(process.env.ACCEPTANCE_POLL_INTERVAL_MS ?? "5000");
const maxPollMs = Number(process.env.ACCEPTANCE_MAX_POLL_MS ?? "1800000");
const resumeSubmitted = process.env.ACCEPTANCE_RESUME_SUBMITTED === "true";
const sourceSubmittedPath = resolve(process.env.ACCEPTANCE_SOURCE_SUBMITTED ?? join(outputRoot, "submitted.json"));
const versions = ["teacher", "student", "lecture"];

/** Resolve a real Poppler binary, bypassing the Codex shim when it is broken on Windows. */
function pdfTool(name, fallbackName) {
  const configured = process.env[name];
  if (configured && existsSync(configured)) return configured;
  const miktex = `C:\\Users\\doob\\AppData\\Local\\Programs\\MiKTeX\\miktex\\bin\\x64\\${fallbackName}.exe`;
  return existsSync(miktex) ? miktex : fallbackName;
}

const pdfInfoBin = pdfTool("PDFINFO_BIN", "pdfinfo");
const pdfToPpmBin = pdfTool("PDFTOPPM_BIN", "pdftoppm");
const pdfToTextBin = pdfTool("PDFTOTEXT_BIN", "pdftotext");
const retryDelayMs = Number(process.env.ACCEPTANCE_RETRY_DELAY_MS ?? "30000");
const templateOverride = process.env.ACCEPTANCE_TEMPLATE_CODE?.trim() || "";
const evidenceLimitOverride = Number(process.env.ACCEPTANCE_EVIDENCE_LIMIT ?? "");

/** Returns a SHA-256 request hash in the exact format expected by the backend. */
function requestHash(body) {
  return `sha256:${createHash("sha256").update(body, "utf8").digest("hex")}`;
}

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${backend}${path}`, {
    ...options,
    headers: { ...(options.headers ?? {}), Accept: "application/json" },
  });
  const text = await response.text();
  let payload = {};
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = { raw: text };
    }
  }
  if (!response.ok) {
    const error = new Error(`HTTP ${response.status} ${path}`);
    error.status = response.status;
    error.payload = payload;
    throw error;
  }
  return payload;
}

async function acquireCapability(session, action, path, body, idempotencyKey, maxCost) {
  const hash = requestHash(body);
  const capability = await jsonRequest("/api/security/capabilities", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      [session.tokenName]: session.tokenValue,
    },
    body: JSON.stringify({ action, path, requestHash: hash, idempotencyKey, maxCost }),
  });
  return { ...capability, requestHash: hash };
}

async function submitTask(session, scenario) {
  const clientRequestId = `acceptance-${scenario.code}-${randomUUID()}`;
  const request = {
    clientRequestId,
    questionText: scenario.questionText,
    learningGoal: scenario.learningGoal,
    evidenceLimit: Number.isFinite(evidenceLimitOverride) && evidenceLimitOverride > 0
      ? evidenceLimitOverride
      : (scenario.evidenceLimit ?? 3),
    ...(templateOverride || scenario.templateCode
      ? { handoutTemplateCode: templateOverride || scenario.templateCode }
      : {}),
  };
  const body = JSON.stringify(request);
  const capability = await acquireCapability(
    session,
    "teaching:submit",
    "/api/teaching/tasks",
    body,
    clientRequestId,
    request.evidenceLimit,
  );
  const startedAt = Date.now();
  const task = await jsonRequest("/api/teaching/tasks", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      [session.tokenName]: session.tokenValue,
      "X-Capability-Token": capability.token,
      "X-Request-Hash": capability.requestHash,
    },
    body,
  });
  return { ...scenario, clientRequestId, taskId: task.taskId, submittedAt: new Date(startedAt).toISOString(), task };
}

async function downloadPreview(session, taskId, version, destination) {
  const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/handout/${version}/pdf/preview`;
  let response;
  let capability;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    capability = await acquireCapability(
      session,
      "teaching-handout:preview-pdf",
      path,
      "",
      `acceptance-preview-${taskId}-${version}-${attempt}`,
      2,
    );
    response = await fetch(`${backend}${path}`, {
      headers: {
        [session.tokenName]: session.tokenValue,
        "X-Capability-Token": capability.token,
        "X-Request-Hash": capability.requestHash,
      },
    });
    if (response.status !== 429 || attempt === 2) break;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, retryDelayMs));
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!response.ok) {
    const error = new Error(`HTTP ${response.status} ${path}`);
    error.status = response.status;
    error.body = bytes.toString("utf8");
    throw error;
  }
  await writeFile(destination, bytes);
  return {
    bytes: bytes.length,
    pageCountHeader: Number(response.headers.get("x-handout-page-count") ?? "0") || 0,
    renderer: response.headers.get("x-handout-renderer") ?? "",
  };
}

async function inspectPdf(pdfPath, pageDir) {
  await mkdir(pageDir, { recursive: true });
  const { stdout: info } = await execFileAsync(pdfInfoBin, [pdfPath], { windowsHide: true });
  const pageMatch = info.match(/^Pages:\s+(\d+)/m);
  const pages = pageMatch ? Number(pageMatch[1]) : 0;
  const prefix = join(pageDir, "page");
  await execFileAsync(pdfToPpmBin, ["-png", "-r", "120", pdfPath, prefix], { windowsHide: true });
  const { stdout: text } = await execFileAsync(pdfToTextBin, ["-layout", pdfPath, "-"], { windowsHide: true, maxBuffer: 5 * 1024 * 1024 });
  // A closing brace can be legitimate mathematics (sets, topology, piecewise expressions). Only flag a complete
  // protocol placeholder pair, never a standalone `}}` emitted by PDF text extraction.
  const forbidden = ["<TODO>", "[PLACEHOLDER]", "system prompt", "内部提示词", "promptTokens", "model_call_"];
  const hits = forbidden.filter((needle) => text.toLowerCase().includes(needle.toLowerCase()));
  if (/\{\{[^\r\n{}]{1,160}\}\}/.test(text)) {
    hits.push("{{...}}");
  }
  return { pages, textChars: text.length, forbiddenHits: hits, textPreview: text.slice(0, 1200) };
}

async function main() {
  await mkdir(outputRoot, { recursive: true });
  const login = await jsonRequest("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  const session = { tokenName: login.tokenName, tokenValue: login.tokenValue };
  const scenarios = [
    { code: "01-topic-only", learningGoal: "二次函数顶点与对称轴", questionText: "", templateCode: "default_standard" },
    { code: "02-question", learningGoal: "解含参数的一元二次函数最值题", questionText: "已知 f(x)=x^2-2ax+1 在区间 [0,2] 上的最小值，讨论 a 的取值。", templateCode: "gaokao_topic_drill_v1" },
    { code: "03-textbook", learningGoal: "高中数学函数单调性教材梳理", questionText: "判断函数 f(x)=x+1/x 在区间 (1,+∞) 上的单调性，并写出证明步骤。", templateCode: "default_standard" },
    { code: "04-question-bank", learningGoal: "涂色问题分类计数", questionText: "五个行政区域相邻不同色，四种颜色共有多少种着色方法？", templateCode: "gaokao_topic_drill_v1" },
    { code: "05-teacher-evidence", learningGoal: "空间向量线面角教师备课", questionText: "在正方体 ABCD-A1B1C1D1 中，求直线 A1B 与平面 BCC1B1 所成角的正弦值。", templateCode: "space_vector_reference_v1" },
    { code: "06-no-evidence", learningGoal: "含参数的非标准复合函数定义题", questionText: "设集合 D={x|x>0}，定义 f(x)=x+1/x，求 f(x) 在 D 上的最小值。", templateCode: "default_standard" },
    { code: "07-feishu-image", learningGoal: "2013 年涂色问题地图图片证据", questionText: "如图，一个地区分为 5 个行政区域，相邻区域不得使用同一颜色，现有 4 种颜色，求不同着色方法数。", templateCode: "default_standard" },
    { code: "08-student-safety", learningGoal: "反比例函数学生版练习", questionText: "已知反比例函数 y=k/x 的图象经过点 (2,3)，求 k，并判断图象所在象限。", templateCode: "gaokao_blank_student_v1" },
    { code: "09-lecture-1610", learningGoal: "16:10 课堂讲解：等差数列求和", questionText: "等差数列 {a_n} 中，a_1=3，公差 d=2，求前 10 项和 S_10。", templateCode: "default_standard" },
    { code: "10-edit-export", learningGoal: "圆锥曲线切线综合复习", questionText: "已知椭圆 x^2/9+y^2/4=1，求过点 (0,2) 的切线方程。", templateCode: "teacher_solution_v1" },
    // These two scenarios exercise the production risks that a one-question image task cannot cover: a directory
    // lesson must yield one real example for each retrieved point, while a quadratic task must keep its source stem,
    // graph requirements, and explanation bound together rather than borrowing an unrelated reference graph.
    { code: "11-directory-multi", learningGoal: "函数新概念与分段函数", questionText: "围绕函数新概念与分段函数，按目录分别给出真实例题和必要变式。", templateCode: "zhao_lixian_2025_master_v1" },
    { code: "12-quadratic-graph", learningGoal: "二次函数顶点与对称轴", questionText: "已知函数 f(x)=x^2-4x+3，求其顶点坐标、对称轴，并说明图像的开口方向。", templateCode: "zhao_lixian_2025_master_v1" },
    // This is the end-to-end publication gate for one fully synchronized source document. The wording names the
    // source and its exact ten-question requirement so the real model cannot substitute a shorter textbook lesson.
    { code: "13-page-backed-gaokao", learningGoal: "2024 全国新课标 II 卷数学真题综合讲评", questionText: "仅使用已同步的《2024全国新课标II卷数学真题（本地解析卷，同源硬链接）》中已导入的 10 道题，按来源题号逐题讲解。每题写出条件识别、推导依据、关键步骤和结论；如图题必须保留同页原图。不要改写为教材例题，不要补造题目。", templateCode: "zhao_lixian_2025_master_v1", evidenceLimit: 10 },
  ];
  const requestedScenarioCodes = (process.env.ACCEPTANCE_SCENARIOS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const selectedScenarios = requestedScenarioCodes.length === 0
    ? scenarios
    : scenarios.filter((scenario) => requestedScenarioCodes.includes(scenario.code));
  if (selectedScenarios.length === 0) {
    throw new Error(`No acceptance scenarios matched ACCEPTANCE_SCENARIOS=${requestedScenarioCodes.join(",")}`);
  }
  const submitted = [];
  if (resumeSubmitted) {
    const previousText = (await readFile(sourceSubmittedPath, "utf8")).replace(/^\uFEFF/, "");
    const previous = JSON.parse(previousText);
    submitted.push(...previous);
  } else {
    for (const scenario of selectedScenarios) {
      submitted.push(await submitTask(session, scenario));
    }
  }
  await writeFile(join(outputRoot, "submitted.json"), JSON.stringify(submitted, null, 2), "utf8");

  // Poll one owner-scoped task detail endpoint at a time. Fetching ten details
  // in parallel trips the production rate limiter; the delay between requests
  // keeps the real API contract intact while all workers continue in parallel.
  const records = new Map(submitted.map((record) => [record.taskId, { ...record, snapshots: [] }]));
  const resolved = new Map();
  const pollingStarted = Date.now();
  // A source snapshot that already contains terminal task payloads is immutable acceptance input; avoid
  // spending rate-limit budget on redundant detail reads before downloading its artifacts.
  if (resumeSubmitted && submitted.every((record) => ["COMPLETED", "FAILED"].includes(String(record.task?.status).toUpperCase()))) {
    for (const record of records.values()) {
      record.snapshots.push({
        at: new Date().toISOString(),
        status: record.task.status,
        completedNodes: (record.task.nodes ?? []).filter((node) => node.status === "completed").map((node) => node.code),
        stageTimings: record.task.stageTimings ?? [],
        errorMessage: record.task.errorMessage ?? null,
      });
      resolved.set(record.taskId, record);
    }
    records.clear();
  }
  while (records.size && Date.now() - pollingStarted < maxPollMs) {
    for (const record of [...records.values()]) {
      let task;
      try {
        task = await jsonRequest(`/api/teaching/tasks/${encodeURIComponent(record.taskId)}`, {
          headers: { [session.tokenName]: session.tokenValue },
        });
      } catch (error) {
        if (error.status !== 429) throw error;
        await new Promise((resolveDelay) => setTimeout(resolveDelay, Math.max(pollIntervalMs, 30000)));
        continue;
      }
      record.task = task;
      record.snapshots.push({
        at: new Date().toISOString(),
        status: task.status,
        completedNodes: (task.nodes ?? []).filter((node) => node.status === "completed").map((node) => node.code),
        stageTimings: task.stageTimings ?? [],
        errorMessage: task.errorMessage ?? null,
      });
      if (["COMPLETED", "FAILED"].includes(String(task.status).toUpperCase())) {
        record.polledMs = Date.now() - pollingStarted;
        resolved.set(task.taskId, record);
        records.delete(task.taskId);
      }
      if (records.size) await new Promise((resolveDelay) => setTimeout(resolveDelay, pollIntervalMs));
    }
  }
  const completed = submitted.map((record) => resolved.get(record.taskId) ?? { ...records.get(record.taskId), timeout: true });

  for (const record of completed) {
    const taskDir = join(outputRoot, record.code);
    await mkdir(taskDir, { recursive: true });
    const artifacts = {};
    if (String(record.task?.status).toUpperCase() === "COMPLETED") {
      for (const version of versions) {
        const pdfPath = join(taskDir, `${version}.pdf`);
        try {
          artifacts[version] = await downloadPreview(session, record.taskId, version, pdfPath);
          artifacts[version].inspection = await inspectPdf(pdfPath, join(taskDir, version));
        } catch (error) {
          artifacts[version] = { error: error.message, status: error.status ?? null, body: error.body ?? null };
        }
      }
    }
    record.artifacts = artifacts;
    await writeFile(join(taskDir, "task.json"), JSON.stringify(record, null, 2), "utf8");
  }
  const summary = completed.map((record) => ({
    code: record.code,
    taskId: record.taskId,
    status: record.task?.status ?? "UNKNOWN",
    errorMessage: record.task?.errorMessage ?? null,
    completedNodes: (record.task?.nodes ?? []).filter((node) => node.status === "completed").map((node) => node.code),
    timings: record.task?.stageTimings ?? [],
    versions: Object.fromEntries(Object.entries(record.artifacts ?? {}).map(([version, artifact]) => [version, {
      pages: artifact.inspection?.pages ?? null,
      forbiddenHits: artifact.inspection?.forbiddenHits ?? [],
      renderer: artifact.renderer ?? null,
      error: artifact.error ?? null,
    }])),
  }));
  await writeFile(join(outputRoot, "summary.json"), JSON.stringify(summary, null, 2), "utf8");
  console.log(JSON.stringify(summary, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
