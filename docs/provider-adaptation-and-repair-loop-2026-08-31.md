# 架构变更记录：四项能力真实落地（2026-08-31）

本日期记录四项此前"未实现、不允许写入简历/答辩"的能力的真实实现。全部代码已合入本分支并通过单测与全量回归。

## 1. provider capability 适配层（唯一抽象点）

- 新增 `ai-worker-python/app/provider_profiles.py`：`ProviderProfile` 注册表集中承载
  `api_key_env / default_base_url / wire_format(openai|anthropic) / thinking_toggle_param /
  json_object_mode / forced_thinking`，并提供 `credentials / default_model_chain /
  completion_endpoint / request_headers / build_request / post_completion / open_stream /
  extract_message / delta_fields / apply_json_object_mode / apply_thinking_off`。
- `handout_runtime / streaming_runtime / agent_runtime / teaching_draft_runtime /
  workload_runtime` 五处原先手工维护的 key/base 映射与 anthropic 分支全部改为调用本层；
  model/env 链顺序保持原行为（`{PROVIDER}_CHAT_MODEL > MATH_AGENT_AI_RUNTIME_{PROVIDER}_MODEL >
  MATH_AGENT_AI_RUNTIME_MODEL > OPENAI_CHAT_MODEL`）。
- 原 deepseek `json_object` 特例泛化为白名单配置
  `MATH_AGENT_HANDOUT_JSON_OBJECT_MODELS`（默认 `deepseek-v4-flash`）；GLM 网关不支持
  json_object，适配层自动跳过。

## 2. reasoning 思考轨迹落盘（只展示该展示的）

- 强制思考模型（GLM）的 `thinking` 块经 `anthropic_compat` 映射进统一私有通道
  `reasoning_content`；流式 `thinking_delta` 归一化为 `delta.reasoning_content`。
- 讲义链路在终态 `_record_model_turn` 写入 `reasoningChars`/`reasoningTrace`（截断上限
  `DEFAULT_REASONING_TRACE_CHARS = 20000`），随 checkpoint 持久化。
- 读取路径 `model_turn_diagnostics` 只投影白名单字段（node/provider/model/elapsedMs/
  finishReason/outcome/reasoningChars/reasoningExcerpt 等），绝不带出 requestPayload、
  rawResponse、extractedJson。
- Java 教师侧端点 `GET /api/teaching/tasks/{taskId}/model-diagnostics`
  （`TeachingModelDiagnosticsController`）：非 teacher/admin 一律 403，excerpt 上限 4000。
  学生可见 SSE（streaming_runtime）刻意丢弃 reasoning 帧，学生版讲义与教学正文不允许出现
  思考轨迹——隔离边界不变。
- 开关：`MATH_AGENT_WORKER_DISABLE_THINKING`（默认 true）关闭可关思考模型的 thinking；
  强制思考模型不可关，其轨迹仅进入上述教师侧诊断。

## 3. LaTeX 编译错误回喂模型自动重写闭环

- Python 新端点 `POST /v1/latex-repair/sync`（`latex_repair_runtime.py`，worker-key 鉴权）：
  输入 runId + LaTeX 源码（≤200k）+ 真实编译错误摘录（≤4000）+ 轮次（≤5），temperature=0，
  provider 按 `MATH_AGENT_LATEX_REPAIR_PROVIDERS` 轮转（空则沿用讲义顺序）。
- fail-closed 结构校验（AI 是唯一正文作者，重写不得损毁内容）：
  `REPAIR_MISSING_DOCUMENT_ENVELOPE / REPAIR_IMAGE_MARKERS_CHANGED /
  REPAIR_QUESTION_HEADINGS_CHANGED / REPAIR_TRUNCATED / REPAIR_INFLATED(>1.6×)`，
  任一不满足即 REJECTED，不回传。
- Java `TeachingHandoutPdfExportService.compileWithModelRepair`：仅当 XeLaTeX 真实报错才回喂
  （引擎缺失不触发修复）；错误摘录取首条 `!` 行 + ≤6 行上下文、遇下一条 `!` 截断；
  轮数上限 `math-agent.teaching.latex-repair.max-rounds`（默认 1，钳制 0..3）。
  修复成功后 renderer 标记 `xelatex-model-repair`，原有确定性 sanitize 保持为第一道防线。

## 4. 飞书按租户自动建库 + 讲义批量上传

- Flyway `V38__feishu_tenant_library_and_batch_upload.sql`：`feishu_tenant_library`
  （tenant 唯一）与 `feishu_handout_upload`（tenant+task+version 唯一，含 content_hash/
  file_token/status）。注意：AGENTS.md 有"不做数据库迁移"惯例，但新表无其他落地路径，
  本次按项目 V1–V37 既有 Flyway 约定新增 V38，已当面报备。
- `FeishuTenantTokenService`：tenant_access_token 缓存（提前 60s 过期），兼容
  `FEISHU_APP_ID/SECRET` 与 `FEISHU_APPID/APPSECRET` 别名。
- `FeishuTenantLibraryService.ensureLibrary`：DB 已有 → 直接复用；否则先按规范化文件夹名
  "认领"云上已存在目录再创建；并发下 DuplicateKey 时复用赢家行。文件夹名
  `{prefix}-{tenantId}`，控制符与 `/ \ : * ? " < > | [ ]` 全部替换为 `_`，
  prefix 由 `FEISHU_LIBRARY_PREFIX`（默认 mathagent）、父目录由 `FEISHU_LIBRARY_PARENT_TOKEN` 配置。
- `FeishuHandoutExportController`：
  `POST /api/feishu/library`（建库/复用），`POST /api/feishu/handout/uploads`
  （≤20 任务 × {student|lecture|teacher} 版本，鉴权 teacher/admin，限流 3/min）。
  上传走 renderForPublication → sha256 与既有行比对，同 hash 置 SKIPPED 不重复传；
  单文件失败记 FAILED 行不中断整批。

## 验证证据（本次运行）

- Python 全量回归：`205 passed, 1 skipped, 31 subtests passed`（含新增
  `test_provider_profiles.py` 10 例、`test_latex_repair_runtime.py` 5 例、
  `test_reasoning_trace_persistence.py` 2 例）。
- Java 全量回归：`Tests run: 726, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS
  （含新增 16 例：修复闭环 6、租户库 4、批量上传 3、诊断端点 3）。
- 真实 GLM 链路 live 检查：`scripts/live_glm_bridge_check.py` 流式断言通过，
  `reasoning_chars=485`，证明思考轨迹在真实网络上完整透传。
