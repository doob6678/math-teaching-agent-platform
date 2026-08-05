# Handout Python Production Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/api/teaching/tasks` the single handout business workflow, with Java as the control/publication plane and one Python LangGraph runtime as the AI execution plane, then prove the result with real Luna, RabbitMQ, MySQL, Redis, Milvus, CUDA, XeLaTeX, and Windows rendering evidence.

**Architecture:** Java owns authenticated subject resolution, task/run state, RabbitMQ lease/ACK/DLQ, authorized evidence, asset access, publication gates, and PDF. Python owns the handout graph, provider routing, structured output, checkpoint/event state, and usage accounting through a least-privileged runtime database account. The existing agent-writing API remains a compatibility facade during a measured canary period; it does not retain a second business workflow.

**Tech Stack:** Spring Boot/MyBatis/Flyway, FastAPI/Pydantic/LangGraph, MySQL, Redis, RabbitMQ, Milvus, Docker Compose in WSL, CUDA, XeLaTeX, Vitest, JUnit, pytest, Playwright.

---

## Current Acceptance

The implementation is a gray release, not complete production convergence. `handout_runtime.py` and the shared checkpoint schema exist, and the recorded Luna run reports 3 successful writer calls in 70.092 seconds with 35,028 total tokens. That is useful evidence, but it is one path and it misses the stated 10,539-token baseline by 3.3x. The Java teaching path still has `PythonTeachingDraftClient`, the agent-writing path still has `MultiAgentWritingService`, stage-level worker code remains, Java model gateways remain, the compose worker receives the Java MySQL root password, and the compose file hard-codes DNS servers. The unfinished acceptance items are recorded in `docs/python-langgraph-handout-migration.md` and `docs/todo/finished/2026-08-04-handout-generation-python-final-finished.md`.

No task may advance past a gate with a missing real artifact, a simulated provider, a CPU fallback for a local model, or an unrecorded failure.

## Task 1: Freeze a Reproducible Baseline

**Files:**
- Create: `scripts/local/run-handout-baseline.sh`
- Create: `scripts/local/summarize-handout-runs.py`
- Modify: `docs/python-langgraph-handout-migration.md`
- Test: `ai-worker-python/tests/test_handout_baseline_summary.py`

- [✅️] **Step 1: Write the failing summary test.** Assert that a run directory with two attempts produces success count, provider success/failure count, elapsed percentiles, prompt/completion/total tokens, estimated cost and `costKnown` without reading authorization headers.
- [✅️] **Step 2: Run the test and verify it fails** with `pytest ai-worker-python/tests/test_handout_baseline_summary.py -q`.
- [✅️] **Step 3: Implement the parser** against the existing `events*.json`, `request-response.json`, `http-metadata.txt`, usage rows and system sample files. Use explicit field names and reject missing terminal events instead of inventing zeroes.
- [✅️] **Step 4: Add the real baseline runner.** It must capture git SHA, Compose service IDs, `docker compose ps`, `nvidia-smi`, `free`, `vmstat`, MySQL/Redis/RabbitMQ/Milvus health, and each run's SHA-256 manifest. It may read existing environment variables but must not change DNS or IP configuration.
- [✅️] **Step 5: Run the parser test and a dry read of the existing acceptance directory.** Expected: the existing run is classified as real evidence, but the report marks missing P95/P99/queue/lease/ACK/PDF metrics as `incomplete`.
- [✅️] **Step 6: Commit** `test: establish non-fabricating handout acceptance baseline`.

## Task 2: Remove Configuration and Credential Violations

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `ai-worker-python/app/usage.py`
- Modify: `ai-worker-python/app/handout_runtime.py`
- Modify: `backend-java/src/main/resources/db/ai-platform-schema.sql`
- Create: `backend-java/src/main/resources/db/runtime-user.sql`
- Test: `ai-worker-python/tests/test_runtime_database_permissions.py`
- Test: `backend-java/src/test/java/com/doob/mathagent/agent/service/RuntimeConfigurationContractTest.java`

- [✅️] **Step 1: Write tests** asserting the worker never receives `MYSQL_ROOT_PASSWORD`, uses a dedicated `MATH_AGENT_AI_RUNTIME_DB_PASSWORD`, and the compose file contains no `dns:` override or host IP mutation.
- [✅️] **Step 2: Run the tests and verify failure.**
- [✅️] **Step 3: Add a least-privileged `ai_runtime` MySQL account** with access only to `handout_checkpoint`, `handout_event`, and `ai_usage_event`; keep Java's business credentials separate. Apply the grant after the tables exist and fail startup when the restricted account is absent.
- [✅️] **Step 4: Change Python checkpoint and usage connections** to the restricted credentials. Keep SQLite only when `MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND=sqlite` is explicitly set for single-process development.
- [✅️] **Step 5: Remove compose DNS entries** and use the already configured service URLs. Verify endpoint resolution from the real WSL Compose network rather than changing DNS.
- [✅️] **Step 6: Run the tests and `docker compose config`; expected: no root DB secret in `ai-worker`, no DNS override, and production startup fails closed on missing runtime credentials.
- [✅️] **Step 7: Commit** `fix: isolate ai runtime credentials and preserve host networking`.

## Task 3: Make the Teaching Task the Only Business Workflow

**Files:**
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowExecutionSupport.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/PythonTeachingDraftClient.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/controller/MultiAgentWritingController.java`
- Modify: `frontend/src/shared/api/textbookApi.ts`
- Modify: `frontend/src/app/components/MultiAgentWritingPanel.tsx`
- Test: `backend-java/src/test/java/com/doob/mathagent/teaching/TeachingWorkflowServiceTest.java`
- Test: `backend-java/src/test/java/com/doob/mathagent/agent/MultiAgentWritingControllerTest.java`
- Test: `frontend/src/shared/api/textbookApi.test.ts`

- [ ] **Step 1: Write tests** asserting that an agent-writing request creates/returns the same `taskId` as a teaching task, `workflowId` is only a compatibility alias, and resume/history/events/export use the teaching-task store.
- [ ] **Step 2: Run the focused Java and Vitest tests and verify failure.**
- [ ] **Step 3: Introduce `HandoutTaskFacade`** at the Java boundary. It validates the authenticated subject, creates one teaching task and `runId`, and delegates all reads/resume/export to the teaching service.
- [ ] **Step 4: Adapt `MultiAgentWritingController`** to call the facade. Do not copy or persist a second workflow row; keep old response fields as aliases until the canary ends.
- [ ] **Step 5: Make the teaching worker call one `/v1/handout-runs/sync` graph.** Retire the separate `/v1/teaching-drafts/sync` call for handout generation; retain that endpoint only for non-handout teaching features with an explicit contract test.
- [ ] **Step 6: Update the frontend API/panel** to use teaching-task endpoints while displaying the existing agent trace fields from the Java projection.
- [ ] **Step 7: Run focused tests and an authenticated HTTP contract test** for create, get, events, resume, feedback and all three artifact versions. Expected: one task row, one run ID, one terminal business state.
- [ ] **Step 8: Commit** `refactor: converge handout entry points on teaching tasks`.

## Task 4: Harden the Cross-Language Contract and Resume Semantics

**Files:**
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/PythonHandoutClient.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/controller/AgentToolBrokerController.java`
- Modify: `ai-worker-python/app/handout_runtime.py`
- Modify: `ai-worker-python/app/server.py`
- Modify: `backend-java/src/main/resources/db/migration/V32__handout_python_checkpoint_event_store.sql`
- Test: `backend-java/src/test/java/com/doob/mathagent/agent/service/PythonHandoutClientTest.java`
- Test: `ai-worker-python/tests/test_handout_runtime.py`
- Test: `ai-worker-python/tests/test_server.py`

- [ ] **Step 1: Add failing contract tests** for `contractVersion`, `runId`, `taskId`, `graphVersion`, `idempotencyKey`, `traceparent`, `deadlineAt`, bounded evidence refs, and error codes. Assert requests containing `tenantId`, `subjectId`, a filesystem path or a Java identity override are rejected.
- [ ] **Step 2: Make Java resolve all authorization from `runId`** and stop serializing `RequestSubject` into the RabbitMQ worker payload. The payload contains only an opaque task ID and a run ID; Java reloads the subject at execution time.
- [ ] **Step 3: Enforce one deadline budget**: `min(client deadline, Python deadline, lease expiry - safety margin)`, with explicit connect/read timeouts and a terminal `MODEL_TIMEOUT` event.
- [ ] **Step 4: Make checkpoint compare `graphVersion` and idempotency key** under a MySQL row lock. An old graph version returns `GRAPH_VERSION_INCOMPATIBLE`; an already completed provider attempt is resumed without another billable call.
- [ ] **Step 5: Run Java/Python contract tests plus a two-worker same-`runId` integration test.** Expected: one completed checkpoint, one usage row per attempt, no duplicate writer call.
- [ ] **Step 6: Commit** `feat: enforce versioned handout run contract and idempotent resume`.

## Task 5: Retire Stage-Level RabbitMQ and Java AI Execution

**Files:**
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/worker/AgentWorkerRabbitConfiguration.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/worker/AgentWorkerTaskConsumer.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunExecutionService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingAiDraftService.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/service/SpringAiOpenAiCompatibleGateway.java`
- Modify: `backend-java/src/main/resources/application.yml`
- Test: `backend-java/src/test/java/com/doob/mathagent/agent/MultiAgentWritingLiveSmokeTest.java`
- Test: `backend-java/src/test/java/com/doob/mathagent/teaching/TeachingWorkflowServiceTest.java`

- [ ] **Step 1: Add a feature-flag contract test** proving old stage dispatch is unavailable for newly created handout tasks while historical rows remain readable.
- [ ] **Step 2: Route only the top-level lecture task** through RabbitMQ. Python owns resource curation, three writers, validation and repair inside the graph; Java owns lease, ACK, DLQ and publication.
- [ ] **Step 3: Remove production wiring for Java provider calls** after the canary flag is permanently on. Keep the classes only in a rollback branch/release, not as a second active runtime.
- [ ] **Step 4: Run `./mvnw -pl backend-java -DskipTests=false test` and the worker pytest suite.** Expected: no new handout path can enqueue four stage tasks and no Java model gateway is called.
- [ ] **Step 5: Commit** `refactor: retire legacy handout stage runtime after canary`.

## Task 6: Complete Operational Metrics and Cost Accounting

**Files:**
- Modify: `ai-worker-python/app/handout_runtime.py`
- Modify: `ai-worker-python/app/usage.py`
- Modify: `backend-java/src/main/java/com/doob/mathagent/agent/worker/AgentWorkerTaskConsumer.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingHandoutPdfExportService.java`
- Create: `backend-java/src/main/resources/db/migration/V33__handout_run_metrics.sql`
- Create: `scripts/local/summarize-handout-runs.py`
- Test: `ai-worker-python/tests/test_usage.py`
- Test: `backend-java/src/test/java/com/doob/mathagent/teaching/HandoutRunMetricsTest.java`

- [ ] **Step 1: Write tests** for immutable per-attempt usage, cached-token fields, unknown pricing (`costKnown=false`, never zero), and duplicate-attempt idempotency.
- [ ] **Step 2: Persist timestamps** for submitted, enqueued, claimed, Python start, each node start/finish, provider attempt, Java context request, publication gate, XeLaTeX, completed/failed, and ACK. Include queue wait, lease wait, ACK latency, PDF time, request/response bytes, retry count, DLQ count, CPU/RSS/GPU samples and trace ID.
- [ ] **Step 3: Add percentile aggregation** for P50/P95/P99 by workflow, provider/model and result status. Do not aggregate missing data as zero.
- [ ] **Step 4: Run the summary script against a real completed run and assert it prints success count, provider call count, tokens, costs, load samples and every missing field.**
- [ ] **Step 5: Commit** `feat: persist end-to-end handout run metrics and cost fields`.

## Task 7: Add Independent Safety and Publication Gates

**Files:**
- Modify: `ai-worker-python/app/handout_runtime.py`
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowLatexRenderer.java`
- Modify: `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingHandoutPdfExportService.java`
- Create: `backend-java/src/main/java/com/doob/mathagent/teaching/service/HandoutPublicationGate.java`
- Test: `ai-worker-python/tests/test_handout_runtime.py`
- Test: `backend-java/src/test/java/com/doob/mathagent/teaching/HandoutPublicationGateTest.java`

- [ ] **Step 1: Write failing fixtures** containing answer leakage, an unbound citation, unauthorized asset ID, malformed `\\frac`, question reordering, missing question, and cross-page continuation.
- [ ] **Step 2: Implement deterministic gates** for teacher/student/lecture outputs, evidence-to-question binding, asset authorization, LaTeX safety, and page/asset manifest checks. Python output alone cannot change Java business state to `COMPLETED`.
- [ ] **Step 3: Compile all three PDFs with real XeLaTeX**, render every page to PNG on Windows, run `scripts/local/audit_handout_layout.py`, and compare PDF/image SHA-256 manifests.
- [ ] **Step 4: Commit** `feat: enforce independent handout publication and layout gates`.

## Task 8: Real Failure, Load, and Canary Verification

**Files:**
- Modify: `scripts/local/run-handout-acceptance.mjs`
- Create: `scripts/local/run-handout-fault-matrix.sh`
- Create: `docs/acceptance/2026-08-05-handout-python-canary.md`

- [ ] **Step 1: Start the real WSL stack** with existing dependencies only: MySQL, Redis, RabbitMQ, Milvus, `ai-worker` with `gpus: all`, backend and the Windows frontend. Record `docker compose ps`, health checks, `nvidia-smi`, `free -h`, `vmstat`, and model device configuration.
- [ ] **Step 2: Run functional matrix** with representative single-question, four-continuous-question, teacher-source, image-asset, student-safety and PDF cases through `/api/teaching/tasks`.
- [ ] **Step 3: Run failure matrix**: kill Python after resource curation, after each writer, kill one worker during parallel writers, restart Java, redeliver the same Rabbit task, cut the Java-Python response, use an old graph version, and force a provider response disconnect after usage. Verify resume and exactly-once usage rows.
- [ ] **Step 4: Run at least 30 real Luna handout runs** at concurrency 1, 5 and 20. Report raw provider success/failure, completed workflow count, retries, duplicate calls, token totals, cost-known rate, P50/P95/P99, queue/lease/ACK/PDF timings and host/container CPU/RSS/GPU load. The acceptance gate is 30/30 completed publication-gated runs, zero student leakage, zero unauthorized assets, zero duplicate billable attempts, and no CPU local-model fallback.
- [ ] **Step 5: Compare against the recorded 231-second/10,539-token baseline.** Require at least 15% end-to-end improvement, prompt tokens no higher than baseline (then a separate 20% reduction target), three writers genuinely parallel, and non-image control P95 at or below 50 ms. Any token regression blocks release even when latency improves.
- [ ] **Step 6: Canary only with `MATH_AGENT_PYTHON_HANDOUT_ENABLED=true`** after all gates pass; retain a measured rollback window, then disable old stage dispatch and remove the Java AI runtime.
- [ ] **Step 7: Commit** `test: record real Luna handout production canary` with redacted request/response metadata, metrics, load samples, PDF manifests and fault outcomes.

## Execution Rules

- Work in the listed order; each task ends with its focused tests and a small commit.
- Do not install a new dependency until `python -m pip list`, `./mvnw -version`, `node_modules`, Docker images and the WSL model mounts have been checked. No DNS, IP or host network changes are allowed.
- Never use fake providers, mocked end-to-end results, fabricated token/cost numbers or CPU fallback for local models. Mocks are allowed only for unit boundaries; every acceptance result must come from the real stack.
- Keep Java's publication gate and business task state authoritative. A Python `COMPLETED` event is only an AI draft completion.
- Stop and fix the failing gate before starting the next task. The final report must list successful and failed calls separately, including retries and token usage for failures.

## 今日执行状态（2026-08-05）

- [✅️] Task 1 已完成并提交：`9bfa04d test: establish non-fabricating handout acceptance baseline`。
- [✅️] Task 2 已完成并提交：`d745bde fix: isolate ai runtime credentials and preserve host networking`。
- [ ] Task 3 正在进行：已开始 `HandoutTaskFacade` 的测试先行改造；尚未完成“唯一教学任务业务流程”的全部读写、恢复、事件与导出收敛，不能提前打钩。
- [ ] Task 4 部分已完成但未达到整项验收：身份覆盖拒绝、缓存 token 账本、版本化 Python 客户端已分别提交为 `2e17dd1`、`9e3f66e`、`901d8e6`、`739fe0f`；仍缺 Java 授权重载、统一 deadline、双 worker 幂等集成验证。
- [ ] Task 5 至 Task 8 尚未完成，后续仅在对应实现、真实验证和独立提交均完成后标记 ✅️。
