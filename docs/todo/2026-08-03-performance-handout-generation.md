# 2026-08-03 讲义生成性能优化 TODO

> 类型：性能、时延、Token、消息队列与吞吐治理
>
> 结论：当前单任务的主要性能瓶颈是模型上下文过大、输出过长和 JSON 修复重试，不是 RabbitMQ 本身。消息队列应继续使用，但需要负责调度、隔离和削峰，不能替代状态库、成本账本和幂等控制。

## 1. 当前真实性能证据

已有真实 Trace 中出现以下数据：

| 阶段/运行 | 耗时或 Token |
|---|---:|
| `resource_curation` | 约 26.7 秒 |
| `teacher_writer` | 约 19.4 秒 |
| `lecture_writer` | 约 59.9 秒 |
| `student_writer` | 约 117.2 秒 |
| `181546Z` 总 Token | 73,206 |
| `185006Z` 总 Token | 52,852 |
| `191753Z` 总 Token | 54,951 |

三个 Writer 虽然并行执行，但整体时延仍由最慢 Writer 决定。当前 `student_writer` 是主要尾延迟来源，并且曾出现两次 JSON 修复重试。

当前缺少完整的队列等待、租约等待、检索、模型、重试、渲染、导出和端到端 P50/P95/P99 指标，因此暂时不能把 RabbitMQ 的耗时占比与模型耗时精确分开。

## 2. P0：降低单任务时延和 Token

### P1. 压缩三个 Writer 的重复上下文

- [ ] 让 `resource_curation` 输出统一的标准资料包。
- [ ] 资料包至少包含知识点、证据片段、题目映射、必要公式、图片证据和样式约束。
- [ ] 三个 Writer 只接收压缩后的资料包，不重复携带完整检索结果、教材原文和题库。
- [ ] 对资料、题目和证据按 hash 去重。
- [ ] 按相关性淘汰低价值资料，超过预算时优先保留题目直接证据和关键公式。
- [ ] 为每个阶段设置独立 `max_input_tokens`，发送请求前执行上下文预算检查。

目标：先降低 Prompt Token，再考虑增加 Worker 数量。当前 5.3 万～7.3 万 Total Token 的输入规模是首要性能问题。

### P2. 限制模型输出长度不用太限制

- [ ] 为 `resource_curation`、`teacher_writer`、`student_writer` 和 `lecture_writer` 设置独立 `max_output_tokens`。
- [ ] 限制每道题的讲解长度、证明步骤、例题数量和重复解释。
- [ ] 将“完整讲义”拆成明确字段和字段级长度约束，避免模型自由扩写。
- [ ] 输出超限时返回受控的结构化失败，不允许无限生成。

### P3. 减少 JSON 修复重试

- [ ] 优先使用 Provider 支持的 JSON Schema 或结构化输出能力。
- [ ] 固定字段顺序、类型和必填字段，减少格式歧义。
- [ ] 修复时只发送错误字段、错误位置和最小必要上下文。
- [ ] 为 JSON repair 设置独立的 Token 预算和最大次数。
- [ ] 修复仍失败时进入 `DRAFT_ONLY`，不能无限重发完整 Prompt。

## 3. P0：利用缓存减少重复计算

- [ ] 缓存同一教材版本的检索结果。
- [ ] 缓存同一题目的标准化证据包。
- [ ] 缓存图片解析和图片证据。
- [ ] 缓存未发生输入版本变化的阶段结果。
- [ ] 缓存 key 必须包含 `tenant`、资料版本、题目版本、Prompt 版本、模型和样式配置。
- [ ] 输入或 Prompt 版本变化时必须自动失效，禁止旧讲义污染新任务。

## 4. P1：消息队列性能治理

RabbitMQ 继续保留，职责是异步调度、削峰、并发隔离、重试和死信处理；MySQL 负责 workflow、stage、Token、成本和最终状态。

### 4.1 按阶段隔离队列

- [ ] 拆分 `resource-curation.queue`。
- [ ] 拆分 `teacher-writer.queue`。
- [ ] 拆分 `student-writer.queue`。
- [ ] 拆分 `lecture-writer.queue`。
- [ ] 拆分 `render.queue` 和 `export.queue`。
- [ ] 为不同队列分别设置并发数、prefetch、优先级和限流策略。

### 4.2 避免队列造成隐性延迟

- [ ] 对长时间模型任务使用低 prefetch，避免 Worker 预取多个任务后造成任务饥饿。
- [ ] 增加每个队列的 queue wait P50/P95/P99。
- [ ] 增加队列积压、消费耗时、Worker 利用率、retry 和 dead-letter 指标。
- [ ] 按租户设置并发上限，避免单个租户占满全部 Worker。
- [ ] 根据 Provider QPS/TPM 动态调整消费并发，不能只按机器 CPU 数量扩容。
- [ ] retry 使用延迟队列和 dead-letter queue，禁止快速失败重试形成消息风暴。

### 4.3 正确处理重复消息

- [ ] 每个 stage 使用 `workflow_id + stage_code + attempt` 作为幂等键。
- [ ] Worker 只有在结果、Token、成本和状态成功持久化后才 ACK。
- [ ] 消费者重复收到消息时，先检查阶段是否已经完成。
- [ ] lease 续租和 fencing token 必须避免慢请求被重复接管。

## 5. P1：模型和渲染分层优化

### 5.1 模型分级路由

- [ ] 资料整理和普通结构化压缩优先使用快速、低成本模型。
- [ ] 教师版和学生版生成使用主模型。
- [ ] 复杂证明、复杂几何和高风险任务才使用高质量审校模型。
- [ ] 普通格式检查优先使用 Java 确定性规则，避免不必要的模型调用。
- [ ] 模型路由必须由配置、预算和质量策略控制，禁止散落硬编码。

### 5.2 渲染链路分段测量和并行

- [ ] 分别记录 `render_queue_wait`、XeLaTeX 编译、PDF 渲染、版式审计和导出耗时。
- [ ] 教师版、学生版和 16:10 版在互不依赖时进入独立渲染队列并行处理。
- [ ] 模板、字体和静态资源使用安全缓存，避免每次重复初始化。
- [ ] 输入内容未变化时复用已验证的 PDF 产物。
- [ ] 性能优化不能跳过真实 XeLaTeX、Poppler 和版式检查。

## 6. P1：建立性能可观测性

### 6.1 必须记录的时间段

```text
queue_wait
lease_wait
retrieval
resource_curation
writer_model
retry
json_repair
render
xelatex_compile
pdf_render
layout_audit
export
total
```

### 6.2 必须记录的 Token 和吞吐指标

- [ ] 各阶段输入 Token、输出 Token和 Total Token。
- [ ] 每秒输出 Token、Provider 请求耗时和错误率。
- [ ] JSON repair rate、Provider retry rate 和重复执行率。
- [ ] 每分钟完成 workflow 数量。
- [ ] 单 Worker 利用率和队列积压量。
- [ ] 单租户 Token、成本和并发占用。

## 7. 建议性能验收指标

以下是建议目标，不是当前已达成结果：

```text
queue wait P95              < 2 秒
普通任务端到端 P95          < 90 秒
复杂任务端到端 P95          < 180 秒
JSON repair rate             < 5%
重复执行率                  = 0
Token 超预算率              = 0
队列死信率                  < 0.1%
```

实际阈值应根据题目数量、资料量、输出版本数和 Provider 限额分档，不能用一个固定数字覆盖所有任务。

## 8. 完整测试数据与证据

### 8.1 讲义生成 Trace：原始文件、去重和阶段明细

`output/mcp-acceptance` 中共有 10 个 `trace.json` 文件，但其中有两组是同一 workflow 的重复验收产物，不能误认为 10 次独立运行。

| 逻辑运行组 | 原始 Trace 文件 | Workflow ID | Model | Prompt | Completion | Total | 阶段数 |
|---|---|---|---|---:|---:|---:|---:|
| A | `164120Z`、`164508Z`、`164632Z`、`164817Z` | `16ad6709-04d6-45fb-b3da-f2849f7ff742` | `gpt-5.6-terra` | 48,607 | 9,837 | 58,444 | 4 |
| B | `181546Z` | `5ab328e6-fd67-4cbb-b912-6f1c717b9869` | `gpt-5.6-terra` | 61,798 | 11,408 | 73,206 | 4 |
| C | `185006Z` | `94ea3900-328a-4c67-b19a-c7c004b18bce` | `gpt-5.6-terra` | 45,200 | 7,652 | 52,852 | 4 |
| D | `190101Z`、`191329Z`、`191753Z`、`195406Z` | `a3bd437c-f5b0-4a42-bd8b-18f75b38e49b` | `gpt-5.6-terra` | 44,517 | 10,434 | 54,951 | 4 |

以上时间目录的完整路径均为：

```text
output/mcp-acceptance/mcp-terra-handout-20260802T164120Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T164508Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T164632Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T181546Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T185006Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T190101Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T191329Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T191753Z/trace.json
output/mcp-acceptance/mcp-terra-handout-20260802T195406Z/trace.json
```

去重后的四次逻辑运行阶段明细如下。`model_call` 单位为毫秒；`repair` 记录该阶段是否出现 JSON 解析失败和重试。

| 运行组 | 阶段 | Model ms | Prompt | Completion | Total | Trace 中 estimatedCost | repair |
|---|---|---:|---:|---:|---:|---:|---|
| A | `resource_curation` | 18,576 | 9,364 | 749 | 10,113 | 0.48 | 无 |
| A | `student_writer` | 78,503 | 14,938 | 4,176 | 19,114 | 0.48 | 1 次 |
| A | `lecture_writer` | 57,649 | 14,118 | 2,921 | 17,039 | 0.48 | 1 次 |
| A | `teacher_writer` | 38,439 | 10,187 | 1,991 | 12,178 | 0.48 | 无 |
| B | `resource_curation` | 26,729 | 10,676 | 1,102 | 11,778 | 0.48 | 无 |
| B | `student_writer` | 117,201 | 24,405 | 6,288 | 30,693 | 0.48 | 2 次 |
| B | `lecture_writer` | 59,885 | 14,875 | 3,043 | 17,918 | 0.48 | 1 次 |
| B | `teacher_writer` | 19,352 | 11,842 | 975 | 12,817 | 0.48 | 无 |
| C | `resource_curation` | 24,800 | 10,676 | 1,171 | 11,847 | 0.48 | 无 |
| C | `lecture_writer` | 27,156 | 6,836 | 1,432 | 8,268 | 0.48 | 无 |
| C | `teacher_writer` | 16,494 | 11,916 | 738 | 12,654 | 0.48 | 无 |
| C | `student_writer` | 78,208 | 15,772 | 4,311 | 20,083 | 0.48 | 1 次 |
| D | `resource_curation` | 27,592 | 10,676 | 1,240 | 11,916 | 0.48 | 无 |
| D | `student_writer` | 37,140 | 6,886 | 1,934 | 8,820 | 0.48 | 无 |
| D | `lecture_writer` | 49,725 | 15,008 | 2,628 | 17,636 | 0.48 | 1 次 |
| D | `teacher_writer` | 87,402 | 11,947 | 4,632 | 16,579 | 0.48 | 无 |

重要数据解释：

- A 组四个时间目录内容相同；D 组四个时间目录内容相同，不能用于计算独立样本 P50/P95。
- B 组的 `student_writer` 达到 117,201 ms，是当前记录中最大的单阶段尾延迟。
- B 组 `student_writer` 发生两次 JSON 解析失败后才在第 2 次重试成功。
- 每个阶段都写入 `estimatedCost = 0.48`，但没有 Provider 价格、实际货币成本和 workflow 级实际成本；该字段不能作为真实成本。
- 以上历史 Trace 使用 `gpt-5.6-terra`，不能代表当前 `.env` 的 `gpt-5.6-luna` 实际模型性能。

### 8.2 教材检索真实性能数据

教材固定 46 条真实 HTTP 请求的对比数据：

| 版本 | 成功数 | 平均 ms | P50 | P95 | P99 | doc@1 | doc@3 | page@1 | page@5 | block@3 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 46/46 | 390.640 | — | 560.966 | — | 0.848 | 0.957 | 0.543 | 0.891 | 0.804 |
| v4 | 46/46 | 344.332 | 331.127 | 460.878 | 798.669 | 0.848 | 0.957 | 0.543 | 0.891 | 0.804 |
| v5 query-route | 46/46 | 414.713 | 403.932 | 571.978 | 600.727 | 0.826 | 0.957 | 0.630 | 0.848 | 0.870 |
| v6 | 46/46 | 352.272 | 354.126 | 461.392 | 512.867 | 0.848 | 0.957 | 0.500 | 0.826 | 0.804 |

教材结论：v4 相比 baseline 平均延迟下降 46.308 ms，P95 下降 100.088 ms，召回指标保持不变；v5 虽然 page@1 增加 8.7 个百分点、block@3 增加 6.5 个百分点，但平均延迟增加 70.381 ms、P95 增加 111.100 ms，不能作为全局默认；v6 牺牲 page@5，不作为默认策略。

教师资料 70 条正例的真实数据：

| 版本 | 平均 ms | P50 | P95 | P99 | doc@1 | doc@3 | doc@5 |
|---|---:|---:|---:|---:|---:|---:|---:|
| legacy mixed | 360.10 | 323 | 446 | 962 | 0.571 | 0.929 | 0.957 |
| two-stage mixed | 378.33 | 337 | 543 | 1061 | 0.629 | 0.814 | 0.971 |
| two-stage specified library | 223.41 | 180 | 361 | 389 | 0.986 | 1.000 | 1.000 |

教师资料按库的 doc@3：

| library | 正例数 | legacy mixed | two-stage mixed | two-stage specified |
|---|---:|---:|---:|---:|
| feishu | 10 | 0.500 | 0.600 | 1.000 |
| gaokao | 7 | 1.000 | 0.857 | 1.000 |
| mock_exam | 9 | 1.000 | 1.000 | 1.000 |
| qq_bundle | 14 | 1.000 | 0.429 | 1.000 |
| teacher_resource | 23 | 1.000 | 1.000 | 1.000 |
| textbook | 7 | 1.000 | 1.000 | 1.000 |

来源：

- `output/benchmarks/retrieval-production-comparison-20260803/summary.md`
- `output/benchmarks/retrieval-optimized-final-v4-20260803/summary.md`
- `output/benchmarks/retrieval-optimized-v5-query-route-20260803/summary.md`
- `output/benchmarks/retrieval-optimized-v6-20260803/summary.md`

报告列出的 v4 未命中小标题块包括：`case-008`、`case-017`、`case-018`、`case-036`、`case-038`、`business-041`、`business-042`、`business-043`、`business-045`；v5 未命中小标题块包括：`case-017`、`case-018`、`business-041`、`business-042`、`business-043`、`business-045`。逐条查询、目标标题、排名和 Top-3 sectionId 以对应 `summary.md` 的完整表格为准。

### 8.3 Python GPU Worker 真实压测数据

压测使用真实鉴权 HTTP 请求，未使用 mock、fake 或模拟分数，测试代码为 `benchmarks/python_worker_load_test.py`。四次报告均使用真实 Worker、CUDA、PyTorch 和 `ThreadPoolExecutor`：

```text
output/benchmarks/python-worker-load-20260722-093232/report.md
output/benchmarks/python-worker-load-20260722-093521/report.md
output/benchmarks/python-worker-load-20260722-093827/report.md
output/benchmarks/python-worker-load-20260722-094253/report.md
```

通用测试配置：

- 请求 timeout：180 秒。
- 并发梯度：1、2、4、8、16、32、64；每档 warm requests 为 10、每档请求数为 8。
- embedding：short、medium、long，batch 1、4、16、32。
- reranker：10、50、100 candidates。
- CLIP：文本和真实 PNG 图片。
- `bge-small-zh-v1.5`、`bge-reranker-v2-m3`、Chinese-CLIP 均使用 CUDA；最终报告记录 `torch 2.11.0+cu128`、CUDA runtime 12.8、RTX 5060 Laptop GPU。
- 所有报告的完整逐请求结果保存在相应目录的 `results.json` 和 `summary.csv`。

每次报告的完整机器数据文件为：

```text
output/benchmarks/python-worker-load-20260722-093232/results.json
output/benchmarks/python-worker-load-20260722-093232/summary.csv
output/benchmarks/python-worker-load-20260722-093521/results.json
output/benchmarks/python-worker-load-20260722-093521/summary.csv
output/benchmarks/python-worker-load-20260722-093827/results.json
output/benchmarks/python-worker-load-20260722-093827/summary.csv
output/benchmarks/python-worker-load-20260722-094253/results.json
output/benchmarks/python-worker-load-20260722-094253/summary.csv
```

四次报告生成时间分别为 `2026-07-22T01:32:32.502377+00:00`、`01:35:21.539768+00:00`、`01:38:27.560258+00:00` 和 `01:42:53.125094+00:00`。前三次模型可用性不同：前两次 Chinese-CLIP 不可用，第三次可用；最终报告记录 CUDA 可用、CUDA runtime 12.8 和 RTX 5060 Laptop GPU。不能把四次报告直接合并为同一组独立样本。

最新 094253 报告各场景升压停止点（所有列均为真实结果，成功率均 100%，错误数均 0）：

| 模型/场景 | 停止并发 | 请求数 | 平均 ms | P50 | P95 | P99 | QPS |
|---|---:|---:|---:|---:|---:|---:|---:|
| embedding short batch 1 | 32 | 256 | 249.37 | 248.60 | 303.72 | 326.98 | 124.346 |
| embedding short batch 4 | 16 | 128 | 132.97 | 133.99 | 166.13 | 180.65 | 117.823 |
| embedding short batch 16 | 16 | 128 | 165.89 | 169.50 | 202.25 | 210.22 | 93.963 |
| embedding short batch 32 | 16 | 128 | 220.41 | 221.74 | 260.90 | 272.27 | 70.210 |
| embedding medium batch 1 | 16 | 128 | 115.59 | 116.62 | 143.48 | 151.51 | 135.859 |
| embedding medium batch 4 | 16 | 128 | 126.80 | 130.78 | 157.99 | 171.58 | 124.059 |
| embedding medium batch 16 | 16 | 128 | 178.50 | 179.78 | 218.51 | 230.45 | 87.510 |
| embedding medium batch 32 | 16 | 128 | 196.57 | 194.78 | 269.28 | 303.54 | 78.390 |
| embedding long batch 1 | 16 | 128 | 116.39 | 117.26 | 147.41 | 152.31 | 133.346 |
| embedding long batch 4 | 8 | 64 | 103.49 | 79.46 | 270.43 | 312.44 | 75.438 |
| embedding long batch 16 | 8 | 64 | 239.31 | 239.58 | 307.51 | 318.89 | 32.249 |
| embedding long batch 32 | 8 | 64 | 463.34 | 469.42 | 569.93 | 614.70 | 16.550 |
| rerank candidates 10 | 8 | 64 | 196.12 | 191.23 | 279.40 | 296.27 | 39.662 |
| rerank candidates 50 | 4 | 32 | 365.86 | 373.64 | 503.05 | 542.56 | 10.618 |
| rerank candidates 100 | 4 | 32 | 701.56 | 718.04 | 1013.25 | 1167.90 | 5.479 |
| CLIP text | 16 | 128 | 167.97 | 169.08 | 210.47 | 233.70 | 92.289 |
| CLIP image | 8 | 64 | 310.65 | 283.03 | 435.66 | 445.82 | 25.244 |

最终报告健康检查：health HTTP 200、约 45.0 ms；capabilities HTTP 200、约 3.03 ms。Manifest cache 首次查询 22.995 ms，重复查询 14.508 ms，报告标记 `reuseObserved=true`。

### 8.4 真实 GPU、容器和消息队列资源采样

教材 v4 与 v5 评测期间的资源采样：

| 指标 | v4 | v5 |
|---|---:|---:|
| GPU 平均利用率 | 14.62% | 17.22% |
| GPU 峰值利用率 | 47.0% | 49.0% |
| GPU 峰值显存 | 5088 / 8151 MB | 5088 / 8151 MB |
| ai-worker CPU 平均/峰值 | 34.39% / 49.36% | 38.37% / 40.32% |
| ai-worker 内存平均/峰值 | 3606.66 / 3607.55 MB | 3606.53 / 3606.53 MB |
| backend CPU 平均/峰值 | 65.84% / 94.32% | 96.34% / 136.75% |
| backend 内存平均/峰值 | 990.79 / 995.80 MB | 875.16 / 884.20 MB |
| RabbitMQ CPU 平均/峰值 | 15.93% / 123.16% | 49.12% / 244.35% |
| RabbitMQ 内存平均/峰值 | 166.61 / 237.40 MB | 166.11 / 241.90 MB |
| MySQL CPU 平均/峰值 | 4.61% / 8.71% | 5.10% / 6.33% |
| Redis CPU 平均/峰值 | 1.12% / 3.54% | 0.87% / 4.18% |

这组数据说明 RabbitMQ 在 v5 压测时存在明显 CPU 峰值，不能简单断言“消息队列没有性能问题”；但当前讲义 Trace 没有 queue wait 字段，仍不能把 RabbitMQ 峰值直接归因于讲义单任务的 117 秒模型尾延迟。必须补齐队列等待和消费耗时 Trace 后再判断。

### 8.5 测试与验收总状态

| 测试类型 | 真实结果 | 结论 |
|---|---|---|
| Java 21 后端定向测试（14:28 记录） | 38 passed、1 skipped | 通过；跳过真实 Noto CJK/XeLaTeX 条件 |
| 四阶段多智能体定向测试（14:55 记录） | 37 tests、0 failures、0 errors、1 skipped | 通过；测试范围与上一条记录不同 |
| 前端 Vitest | 21 个测试文件、102 个测试通过 | 通过 |
| 前端 `tsc && vite build` | 构建成功，仅 bundle 大小提示 | 通过 |
| 全量后端 `mvn test` | 756 tests、52 failures、15 errors、8 skipped | 未通过，不能宣称全量通过 |
| 教材检索真实评测 | 46/46 成功 | 通过，但不同策略召回/延迟有权衡 |
| Python Worker GPU 压测 | 各停止点成功率 100%、错误数 0 | 通过；需按并发和输入长度分档限流 |

全量 Maven 失败集中在 Windows 中文编码、fake-xelatex/真实 XeLaTeX 与 Noto CJK 环境差异、旧教师检索排序断言，以及测试数据和异步时序耦合。

### 8.6 当前没有测到的数据

以下数据目前没有真实完整结果，不能补写成估算值：

- 讲义 workflow 的 RabbitMQ queue wait P50/P95/P99。
- 讲义 workflow 的 Worker lease wait、ACK 延迟和重复投递率。
- 讲义端到端 P50/P95/P99。
- 讲义按租户并发下的吞吐和成本曲线。
- `gpt-5.6-luna` 与历史 `gpt-5.6-terra` 的同输入对照性能。
- 讲义实际货币成本，因为当前 Trace 只有 Token 和固定 `estimatedCost`，没有真实价格表。

这些项目必须通过真实压测和实际 Provider usage 补齐，不能根据现有单次 Trace 推断。

## 9. 推荐实施顺序

```text
上下文压缩
  -> 输出长度限制
  -> JSON Schema 与局部修复
  -> 检索/资料包缓存
  -> 阶段队列隔离
  -> 队列与模型耗时指标
  -> 模型分级路由
  -> PDF 渲染并行
  -> 动态并发和租户限流
```

不要先简单增加 RabbitMQ Worker 数量。当前单任务的主要瓶颈是模型输入、输出和重试；盲目扩容只会提高 Provider 并发、Token 消耗和成本，不能解决 `student_writer` 约 117 秒的尾延迟。

## 10. 相关源码和文档

- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/SpringAiOpenAiCompatibleGateway.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/config/AgentWorkerRabbitConfiguration.java`
- `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowExecutionSupport.java`
- `frontend/src/app/components/MultiAgentWritingPanel.tsx`
- `docs/workflow-token-latency-optimization-2026-07-28.md`
- `docs/mcp-math-pdf-production-acceptance.md`
- `docs/todo/2026-08-03-handout-generation-audit.md`

最后更新：2026-08-03（Asia/Shanghai）
