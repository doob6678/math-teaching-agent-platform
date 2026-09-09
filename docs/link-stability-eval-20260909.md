# AI 链路稳定性与负载实测报告（2026-09-09）

> 纪律声明：本报告所有数字均来自真实运行输出（curl / python requests / docker stats / ss / netstat），禁止编造或"合理推测"冒充测量。脚本位于 `tools/link-eval-20260909/`。本任务只测不改，未修改任何应用代码，未执行 docker prune，未重启非本次启动的服务。

## 1. 目标

用真实测量得到本仓库 AI 链路（Java 网关 → Milvus 检索 → ai-worker BGE/Rerank → LLM）的稳定性与负载具体数据，覆盖：

1. **服务盘点**：探测各服务真实存活状态与端口，区分 Windows 侧与 WSL 侧。
2. **检索链路稳定性（RAG）**：连续多轮重复查询，量化 doc@k 命中率方差、延迟分布（p50/p95）、错误率。
3. **负载实测**：1/5/10 并发梯度压测代表接口，记录吞吐、延迟分位、错误率，并同步采样容器资源。
4. **收口**：汇总、异常清单、稳定性结论、瓶颈初判、建议。

## 2. 环境清单

| 项 | 值 | 来源 |
|---|---|---|
| 主机 | win32 10.0.26200 x64, Git Bash | 任务环境 |
| 仓库根 | C:\Users\doob\Desktop\code\dev\math_agent_rag | — |
| Docker 容器栈 | WSL2（NAT），经 Docker Desktop 转发到 Windows host 端口 | .env / docker-compose.yml |
| backend-java | server.port=8080, host 端口 8080 | application.yml / .env MATH_AGENT_BACKEND_HOST_PORT |
| ai-worker-python | 容器内 8091，host 端口 8092 | .env MATH_AGENT_WORKER_HOST_PORT |
| MySQL | 容器 3306，host 3307 | .env MATH_AGENT_MYSQL_HOST_PORT |
| Redis | 容器 6379，host 6380 | .env MATH_AGENT_REDIS_HOST_PORT |
| RabbitMQ | 容器 5672/15672，host 5674/15674 | .env |
| Milvus | 容器 19530，host 19531 | .env MATH_AGENT_MILVUS_HOST_PORT |
| GPU | CUDA（BGE text/rerank/clip device=cuda） | .env |
| python 压测环境 | D:\conda\envs\py_12（requests, ThreadPoolExecutor） | 任务指定 |

## 3. 阶段计划

- 阶段 1：服务盘点（真实探测）— Windows netstat/curl + WSL ss/curl + docker ps。
- 阶段 2：检索链路稳定性（RAG）— 后端教材检索接口连续 ≥3 轮 × ≥20 条真实查询词，记录延迟 / doc@k / 错误 / Milvus 响应。
- 阶段 3：负载实测 — 1/5/10 并发梯度压检索 + 学生讲解同步 + 健康检查，每档 ≥20 请求，同步 docker stats。
- 阶段 4：收口 — 汇总表、异常清单、稳定性结论、瓶颈初判、建议。

## 4. 阶段 1：服务盘点实测

探测时间：2026-09-09。方法：Windows `netstat` + `curl`；WSL `wsl.exe bash` 跑 `docker ps / ss -tlnp / docker exec curl / docker stats`（脚本 `tools/link-eval-20260909/wsl_probe.sh`）。未启停任何服务（全部为已有容器，Up 12 minutes，均 healthy）。

### 4.1 容器与端口映射（docker ps 真实输出）

| 容器 | 状态 | 端口映射（host→container） |
|---|---|---|
| math-agent-rag-backend-1 | Up (healthy) | 8080→8080 |
| math-agent-rag-ai-worker-1 | Up (healthy) | 8092→8091 |
| math-agent-rag-milvus-1 | Up (healthy) | 19531→19530, 9092→9091 |
| math-agent-rag-mysql-1 | Up (healthy) | 3307→3306 |
| math-agent-rag-redis-1 | Up (healthy) | 6380→6379 |
| math-agent-rag-rabbitmq-1 | Up (healthy) | 5674→5672, 15674→15672 |
| math-agent-rag-milvus-etcd-1 | Up (healthy) | 2379-2380（内部） |
| math-agent-rag-milvus-minio-1 | Up (healthy) | 9000（内部） |
| math-agent-rag-frontend-1 | Up (healthy) | 80→80 |

### 4.2 Windows 侧探活（netstat + curl）

- 目标端口 3306/3307/5674/6379/6380/8080/8092/15674/19531 全部 `LISTENING`（PID 33056 = Docker Desktop WSL 端口转发进程）。
- `curl http://127.0.0.1:8080/actuator/health` → **HTTP 200，0.094s**。
- `curl http://127.0.0.1:8080/` → HTTP 500（根路径无 controller，属预期，非服务故障）。
- `curl http://127.0.0.1:8092/health`（worker）→ **HTTP 200，0.003s**，body `{"status":"UP","service":"math-agent-rag-worker"}`。
- 容器内直探 `docker exec backend curl :8080/api/system/health` → **HTTP 200，0.073s**。

### 4.3 资源基线（docker stats --no-stream 冷采样）

| 容器 | CPU% | MEM |
|---|---|---|
| backend | 1.03% | 1.04 GiB / 15.25 GiB |
| ai-worker | 0.25% | 4.13 GiB（BGE/rerank 模型常驻） |
| milvus | 5.34% | 218 MiB |
| mysql | 1.20% | 477 MiB |
| redis | 0.40% | 7.3 MiB |
| rabbitmq | 90.25%（首采样瞬时值，待负载阶段复采确认） | 245 MiB |

> 注：`docker stats --no-stream` 首次采样存在 CPU 计数瞬时抖动，rabbitmq 90% 需在阶段 3 复采并观察是否稳定；此处如实记录原始值。

### 4.4 阶段 1 结论

- **全部核心服务在跑**：backend、ai-worker、Milvus、MySQL、Redis、RabbitMQ 均 healthy，无需本次启动。
- **链路可用（真实 smoke）**：登录 admin 成功；教师检索 `GET /api/teacher/resources/search` → 200 / 2880ms（含 BGE 嵌入 + Milvus + GPU rerank 全链路）；教材检索 `GET /api/retrieval/textbooks/search` → 200 / 667ms，响应含 per-stage `elapsedMs`（bge_page=100ms、bge_rerank=270ms、lexical_bm25=75ms），可拆层定位瓶颈。

## 5. 阶段 2：检索链路稳定性实测

### 5.1 阶段 2A：教师资源检索 doc@k 稳定性（真实后端全链路）

- 测量对象：`GET /api/teacher/resources/search`（BGE 查询嵌入 + Milvus + GPU cross-encoder 两段式重排 + RRF，即完整生产 RAG 链路）。
- 数据源：仓库既有真实人工标注集 `benchmarks/datasets/teacher_math_manual_annotated_20260830.json`，取 24 条带 `expected_document_id/expected_block_id` 的真实中文题干查询。
- 方法：连续 **3 轮**重复同一 24 条查询（72 请求），轮间隔 5s；客户端 `max_retries=1` 以让真实错误暴露。脚本 `tools/link-eval-20260909/phase2_rag_stability.py`，原始行 `output/link-eval-20260909/phase2a_rag_rows_*.jsonl`，汇总 `phase2a_rag_summary_*.json`。

| 轮次 | doc@3 | doc@5 | block@3 | block@5 | 错误率 | 重试/429 | avg | p50 | p95 | max |
|---|---|---|---|---|---|---|---|---|---|---|
| Round 1 | 1.000 | 1.000 | 1.000 | 1.000 | 0/24 | 0 / 0 | 470ms | 470ms | 568ms | 1618ms |
| Round 2 | 1.000 | 1.000 | 1.000 | 1.000 | 0/24 | 0 / 0 | 368ms | 368ms | 400ms | 490ms |
| Round 3 | 1.000 | 1.000 | 1.000 | 1.000 | 0/24 | 0 / 0 | 433ms | 433ms | 658ms | 1398ms |
| **总体(72)** | **1.000** | **1.000** | **1.000** | **1.000** | **0.0%** | 0 / 0 | **423ms** | — | **568ms** | 1618ms |

- **命中率方差**：跨轮 doc@3 标准差 = **0.0**，doc@5 标准差 = **0.0**；逐查询跨 3 轮命中一致性 **24/24**（完全确定性，同一查询稳定返回同一文档）。
- **交付门禁判定**（仓库口径 doc@3≥0.80、block@3≥0.60）：**全部通过**，且远超门槛（实测 1.00）。
- **稳定性结论**：检索结果**高度稳定**——0 错误、0 限流、0 重试、排序完全可复现。唯一波动来自**延迟**：存在偶发慢样本（max 1.6s，出现在 GPU rerank 冷批 / 与其它 AI 任务争抢 CUDA 时），但 p95 稳定在 400–658ms。
- **缓存说明（诚实标注）**：后端对教材检索启用 Redis search-cache（`ttl 10m`）；本轮重复同一 24 查询，warm 轮可能命中缓存，因此 100% 命中 + 低延迟部分体现"缓存后"路径。冷路径首探（阶段 1 smoke，进程首次调用）实测端到端 **2880ms**，为模型/索引预热成本；本表 warm p50≈370–470ms 为稳态口径。

### 5.2 阶段 2B：Milvus 直连检索延迟（隔离向量层）

- 方法：`pymilvus 2.6.17` 直连 host:19531（root 认证），随机 512 维查询向量 × 50 次、top-k=20，排除 BGE 嵌入与 rerank。脚本 `phase2_milvus_direct.py`，输出 `output/link-eval-20260909/phase2b_milvus_direct.json`。

| Collection | 维度 | 行数 | p50 | p95 | max | avg | stdev | 错误 |
|---|---|---|---|---|---|---|---|---|
| math_agent_teacher_text_blocks_bge | 512 | 6678 | 3ms | 5ms | 402ms | 11ms | 56 | 0/50 |
| textbook_text_collection | 512 | 3116 | 4ms | 5ms | 21ms | 4ms | 2 | 0/50 |

- **结论**：Milvus 向量检索**不是瓶颈**——FLAT/COSINE 精确检索 p50 3–4ms、p95 5ms。teacher 集合 max 402ms 为 query node 首次加载冷启动；稳态 stdev≤2–56ms。相较端到端 423ms，**向量检索仅占 ~1%**，时间主要花在 BGE 查询嵌入 + GPU cross-encoder 重排。

## 6. 阶段 3：负载实测

- 方法：`D:\conda\envs\py_12\python.exe`（requests + ThreadPoolExecutor），一次登录取 `satoken` cookie 注入各线程独立 Session；**raw 结果不重试**，如实区分 200 / 429 / 5xx / 连接异常。脚本 `phase3_load.py`，明细 `output/link-eval-20260909/phase3_load_*.json`。并发梯度 **1 / 5 / 10**；检索与健康每档 20 请求；学生讲解 LLM 档控制总量（1+5+10=16 ≤30，最小 prompt，真实外呼产生费用）。
- 采样：`stats_sampler.sh` 在 WSL 每 2s 采 `docker stats --no-stream`（共 62 样本）写入 `output/link-eval-20260909/docker_stats.log`。

### 6.1 健康检查 `GET /api/system/health`（网关/DB 探测，轻）

| 并发 | 请求 | 成功 | 429 | 5xx | 吞吐(rps) | p50 | p95 | max | 错误率 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 20 | 20 | 0 | 0 | 26.5 | 37ms | 46ms | 51ms | 0% |
| 5 | 20 | 20 | 0 | 0 | 148.6 | 32ms | 37ms | 41ms | 0% |
| 10 | 20 | 20 | 0 | 0 | 181.6 | 42ms | 51ms | 63ms | 0% |

> 补充：actuator readiness 组内的 `infrastructureDependencies` 健康贡献者在负载窗口被日志记录**单次耗时 16104ms**（WARN）——依赖探测在 worker 繁忙时会阻塞就绪判定，是运维侧隐患（非吞吐接口，不影响上面的轻健康端点）。

### 6.2 教材检索 `GET /api/retrieval/textbooks/search`（BGE 嵌入 + Milvus + GPU rerank）

| 并发 | 请求 | 成功 | 429 | 5xx | 吞吐(rps) | p50 | p95 | max | 错误率 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 20 | 20 | 0 | 0 | 7.1 | 102ms | 300ms | 432ms | 0% |
| 5 | 20 | 20 | 0 | 0 | 50.2 | 80ms | 111ms | 112ms | 0% |
| 10 | 20 | 20 | 0 | 0 | 93.2 | 88ms | 95ms | 109ms | 0% |

- 吞吐随并发近线性扩展到 **93 rps**，p95 稳定 ≤111ms，**0 错误**。conc1 max 432ms 为冷启动首包；warm 后 Redis search-cache + 稳定 rerank 使其成为最快检索路径。

### 6.3 教师检索 `GET /api/teacher/resources/search`（两段式 doc+block + RRF，GPU 重排重）

| 并发 | 请求 | 成功 | 429 | 5xx | 吞吐(rps) | p50 | p95 | max | 错误率 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 20 | 20 | 0 | 0 | 2.2 | 432ms | 598ms | 1000ms | 0% |
| 5 | 20 | 20 | 0 | 0 | 5.6 | 832ms | 1119ms | 1130ms | 0% |
| 10 | 20 | 20 | 0 | 0 | **5.1** | 1875ms | 2146ms | 2170ms | 0% |

- **吞吐在 ~5.5 rps 饱和**：并发从 5→10 吞吐不升反降（5.58→5.14），p50 近乎线性恶化（432→832→1875ms，约 2x/4x）。这是 **GPU cross-encoder 重排串行化排队**特征——单 GPU rerank 资源无法并行，多余请求排队，正确性无损（0 错误）但延迟随并发累积。

### 6.4 学生讲解同步 `POST /api/students/explanations`（真实外呼 LLM）

| 并发 | 请求 | 成功 | 429 | 5xx | p50 | p95 | max | 错误率 |
|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 0 | 0 | 10910ms | 10910ms | — | 0% |
| 5 | 5 | 4 | — | 1 | 8395ms | 10432ms | 10432ms | 20% |
| 10 | 10 | 5 | 5 | 5* | 8665ms | 13659ms | 13659ms | 50% |

> *两类失败并存（诊断脚本 `student_500_probe.py` + backend/worker 日志证实）：
> - **429**：body `{"code":"API_ACCESS_DENIED","message":"Rate limit exceeded","limit":20,"used":21}`——Java 侧 **per-subject Redis 滑窗限流 = 20/window**，快速拒绝（<40ms）。
> - **500**：backend `GlobalApiExceptionHandler` → `IllegalStateException: Python worker streaming request failed`，Caused by `I/O error POST http://ai-worker:8091/v1/student-explanations/stream ... EOF reached while reading (chunked READING_LENGTH)`；worker 侧根因 `HTTPException 409 STUDENT_EXPLANATION_RUN_FINGERPRINT_MISMATCH` 抛在**已开流的 SSE 内部**（`RuntimeError: Caught handled exception, but response already started`）→ 流断裂 → Java 500。worker `student_explanation_runtime._stream_executor = ThreadPoolExecutor(max_workers=4)`，**学生讲解并发上限仅 4**，超出的并发触发指纹冲突/流失败。
- 单条讲解端到端 **8–14s**（真实 LLM ReAct + 思考），10 并发下**有效成功率仅 5/10**，且**不是限流就是真错**——AI 讲解路径并发扩展性差，是链路稳定性最薄弱一环。

### 6.5 压测期资源峰值（docker stats 62 样本取各容器最大值，host 16GiB / WSL2）

| 容器 | 峰值 CPU | 峰值 MEM | 说明 |
|---|---|---|---|
| backend | 234.9% | 1518 MiB | 多线程处理并发，余量充足 |
| ai-worker | 165.6% | 4276 MiB | BGE/rerank 模型常驻 4.2GiB |
| milvus | 51.2% | 304 MiB | 向量层最轻 |
| mysql | 58.4% | 599 MiB | 讲解写库 |
| redis | 8.2% | 9 MiB | 检索缓存命中，负载极低 |
| **rabbitmq** | **303.4%** | 253 MiB | **异常**：listeners 配置为 disabled 却持续吃 ~3 核（冷采样即 90%），疑似 erlang 调度/重连 churn，与本次 HTTP 负载无直接因果，需运维单独排查 |

- 内存均远在 15.25GiB 限额内，未见 OOM；CPU 瓶颈未饱和（多核），**瓶颈是 GPU rerank 串行 + worker 4 线程流执行器 + per-subject 限流**，不是 CPU/内存/向量库。

## 7. 阶段 4：汇总与结论

### 7.1 核心矩阵：并发 × 接口 → p95 / 错误率

| 接口 | 并发1 p95 | 并发5 p95 | 并发10 p95 | 错误率(1/5/10) | 吞吐饱和点 |
|---|---|---|---|---|---|
| 健康 `/api/system/health` | 46ms | 37ms | 51ms | 0% / 0% / 0% | 181 rps（线性） |
| 教材检索 `/api/retrieval/textbooks/search` | 300ms | 111ms | 95ms | 0% / 0% / 0% | 93 rps（线性） |
| 教师检索 `/api/teacher/resources/search` | 598ms | 1119ms | 2146ms | 0% / 0% / 0% | **~5.5 rps 饱和** |
| 学生讲解 `/api/students/explanations`(LLM) | 10910ms | 10432ms | 13659ms | 0% / 20% / 50% | ~0.7 rps（受 4 线程 + 限流） |

RAG 稳定性（阶段 2）：教师检索 3 轮 × 24 真实题干 = 72 请求，**doc@3/doc@5/block@3/block@5 均 100%、0 错误、跨轮命中率方差 0.0（24/24 完全确定性）、warm p95 568ms**，远超交付门禁（doc@3≥0.80、block@3≥0.60）。Milvus 直连 p50 3–4ms / p95 5ms / 0 错误。

### 7.2 异常清单（错误样本原文）

1. **429 — per-subject 限流（预期保护，但阈值低）**
   `{"code":"API_ACCESS_DENIED","message":"Rate limit exceeded","limit":20,"used":21}`
   触发：学生讲解 10 并发；Redis 滑窗，每主体每窗口 20。
2. **500 — Java→ai-worker SSE 流断裂（真实缺陷）**
   backend：`IllegalStateException: Python worker streaming request failed` / `I/O error on POST http://ai-worker:8091/v1/student-explanations/stream: EOF reached while reading (chunked transfer encoding, state: READING_LENGTH)`
   worker：`HTTPException 409: STUDENT_EXPLANATION_RUN_FINGERPRINT_MISMATCH` 抛于 `student_explanation_runtime.stream_events`（已开流后）→ `RuntimeError: Caught handled exception, but response already started`。
   触发：学生讲解并发 > worker 4 线程执行器。
3. **16104ms — readiness 健康贡献者阻塞**
   `InfrastructureDependenciesHealthIndicator took 16104ms`（WARN），worker 繁忙时依赖探测拖慢就绪判定。
4. **rabbitmq 峰值 303% CPU（环境异常）**
   listeners 配置 disabled 却持续高 CPU，冷采样即 90%；与本次 HTTP 负载无因果，疑 erlang 调度/重连 churn，建议运维排查。

### 7.3 稳定性结论

- **检索链路（RAG）稳定且高质量**：确定性排序、0 错误、0 限流（检索路径）、命中率方差 0；10 并发下教师/教材检索**保持 0% 错误**，可放心作为"10 并发稳定"简历口径。
- **AI 讲解链路不稳定**：并发 ≥5 起出现 20%、10 并发 50% 失败；根因是 worker 4 线程流执行器 + SSE 中途抛错 + 低限流阈值。**"10 并发稳定"仅对检索/健康成立，对同步 AI 讲解不成立。**

### 7.4 瓶颈初判（分层）

| 层 | 判定 | 证据 |
|---|---|---|
| 向量库 Milvus | **非瓶颈** | 直连 p50 3–4ms，负载下 CPU 51% |
| 网关/DB（Java/MySQL/Redis） | **非吞吐瓶颈** | 健康 181rps、教材 93rps、0 错误；Redis 仅 8% CPU；但 per-subject 限流 20 是硬约束 |
| GPU 重排（BGE cross-encoder rerank） | **教师检索吞吐瓶颈** | 教师检索 5.5rps 饱和、p50 随并发 2x/4x 线性恶化（排队）；单 rerank stage 实测 ~270ms |
| ai-worker 学生讲解流执行器 | **AI 讲解正确性瓶颈** | max_workers=4；超并发触发 409 指纹冲突→SSE 断→500，错误率 50%@conc10 |

### 7.5 建议（不改动，仅结论）

1. 教师检索若要冲更高并发：扩 GPU rerank 并行度或批处理合并候选（当前单资源串行，5.5rps 封顶）。
2. 学生讲解：提高 `_stream_executor` 并发并在流已开始后**禁用会抛 HTTPException 的校验路径**（409 应前置到开流前返回），否则并发一高即 500。
3. per-subject 限流 20/window 对"讲解"过紧，建议按端点分级；同时修复 SSE 错误后 `response already started` 的兜底。
4. 排查 rabbitmq idle 高 CPU；将 `infrastructureDependencies` 健康探测加超时，避免 worker 繁忙拖垮 readiness。

### 7.6 测量口径与边界（诚实声明）

- 全部数据来自真实运行输出，无编造。脚本与原始产物：`tools/link-eval-20260909/`、`output/link-eval-20260909/`。
- **未启动/未重启任何服务**（9 个容器本次运行前即 Up/healthy）；未改应用代码；未执行 docker prune。
- 教材/教师检索 warm 结果受 Redis search-cache（ttl 10m）影响，重复查询命中缓存，故阶段 2 的 100% 一致含缓存成分；冷首包实测 2880ms 已单独标注。
- 学生讲解 LLM 为真实外呼（GLM），共产生约 25 次调用（阶段 3 + 500 诊断），为最小 prompt，**已发生真实 API 费用**，如实记录。
