# 2026-08-03 讲义生成系统调查 TODO

> 类型：架构、质量、时延、Token、成本、进度与生产化治理调查
>
> 调查结论：当前系统工程化基础较强，已经明显超过普通 Demo；但成本预算未真正执行、分布式一致性仍有风险、进度和指标体系不完整、独立审校链路被压缩，因此暂不能认定为一流水平生产系统。
>
> 本文记录调查发现和可执行整改项，不代表下列 TODO 已完成。

## 1. 当前架构事实

### 1.1 主教学 DAG

当前主教学链路为：

```text
任务创建
  -> 证据检索
  -> 题目子智能体
  -> Courseware 结构化草稿
  -> Java 渲染教师版、学生版、16:10 版
  -> XeLaTeX 编译
  -> PDF SHA-256、Poppler 渲染、版式审计
  -> 持久化状态与 SSE 快照流
```

### 1.2 多智能体写作链路

当前实际执行拓扑为四个模型阶段：

```text
resource_curation
       -> teacher_writer  -+
       -> student_writer  -+-> 汇总与导出
       -> lecture_writer  -+
```

三个 Writer 并行执行。以下六个旧阶段目前仅保留兼容契约，没有接入当前执行链：

- `template_selection`
- `outline_planning`
- `source_review`
- `student_safety_review`
- `layout_review`
- `merge_coordinator`

该拓扑有利于控制 Token 和延迟，但也意味着内容审校、安全审校和布局审校更多依赖 Java 导出层门禁，而不是独立审校 Agent 闭环。

## 2. 已验证的优势

- MySQL 持久化 workflow 状态，支持失败后的恢复。
- RabbitMQ 异步任务队列、Worker lease、有限重试和并发控制已具备。
- 三个 Writer 并行，检索分支也支持并行。
- 具备租户、主体和权限隔离。
- 教师版、学生版和 16:10 版产物相互隔离。
- 有学生版答案泄漏门禁、教师版来源标注门禁、JSON 结构校验、主题一致性和题目覆盖率校验。
- 有公式、裸 TeX、分隔符、图片权限、URI 脱敏等校验。
- 使用真实 XeLaTeX 编译 PDF，并进行 SHA-256、Windows Poppler 全页渲染和版式审计。
- Trace 已记录阶段、Provider、Model、Prompt Token、Completion Token、Total Token 和阶段耗时。
- 主教学链路的 SSE 以持久化快照为基础，并只在状态指纹变化时推送，具备恢复型进度能力。

## 3. P0：必须优先处理

### H1. 成本计算不是实际成本

- [ ] 接入按 Provider 和 Model 区分的真实价格表，至少区分输入 Token、输出 Token、缓存 Token，并保存 `pricingVersion`。
- [ ] 计算并持久化 `actualInputCost`、`actualOutputCost`、`actualTotalCost`、币种和价格版本。
- [ ] 不再使用固定 `totalTokens / 10000` 作为实际成本；该值最多只能作为无价格配置时的临时估算，并必须明确标记为估算。
- [ ] 为每个阶段和整个 workflow 建立成本账本，支持按租户、用户、模型、任务和日期查询。

证据位置：

- `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunPlanService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowExecutionSupport.java`
- `.env` 当前未配置真实模型价格表。

### H2. 预算没有真正阻断模型调用

- [ ] 增加 workflow 级总 Token 预算和总成本预算。
- [ ] 将 Provider retry、JSON repair retry、降级调用全部计入同一预算。
- [ ] 在模型执行入口强制检查 `withinBudget`，预算不足时必须拒绝调用，而不是只生成计划信息。
- [ ] 增加预算超限策略：压缩上下文、降低输出规格、切换低价模型、进入人工审核或终止。
- [ ] 记录预算拒绝原因，禁止以“估算成本”代替真实预算检查。

证据位置：

- `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunExecutionService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunPlanService.java`

### H3. Worker lease 可能早于模型请求完成

- [ ] 校准任务 lease、Provider total timeout、HTTP read timeout、retry 总时长和状态提交时间。
- [ ] 保证 `lease > 最大请求时间 + 最大重试时间 + 状态提交时间`，或实现可靠的 lease 续租。
- [ ] 增加重复接管检测和幂等计费保护。
- [ ] 对“模型仍在执行但 lease 已过期”的情况增加集成测试和告警。

当前风险：顶层任务 lease 约为 300 秒，而部分 Docker AI 请求 timeout 可达到 420 秒，可能导致重复接管和重复模型调用。

## 4. P1：生产化必须处理

### H4. 多实例 workflow 状态可能互相覆盖

- [ ] 为 workflow snapshot 增加 `version` 或 `revision`。
- [ ] 使用数据库 CAS 更新、行锁或等价的原子合并更新。
- [ ] 阶段结果采用按 stage 的幂等写入，禁止用旧快照覆盖其他 Worker 已提交的阶段。
- [ ] 增加两个及以上 Worker 并发更新同一 workflow 的真实集成测试。
- [ ] 逐步移除仅依赖 `synchronized (workflowId.intern())` 的跨阶段一致性假设。

证据位置：

- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/store/MyBatisMultiAgentWritingWorkflowStore.java`

历史现象：多个 Writer 并行完成后，旧快照互相覆盖，workflow 曾长时间显示 `RUNNING`，且 `stageCount=2`。

### H5. 进度只有离散阶段百分比

- [ ] 建立事件驱动的进度模型，至少覆盖排队、租约、检索、模型调用、JSON 修复、渲染、编译、版式审计和导出。
- [ ] 展示当前阶段的重试次数、已用 Token、已用成本、预算比例和阻塞原因。
- [ ] 增加 queue wait、lease wait、retrieval、model、retry、render、export 和 total latency。
- [ ] 为每类任务计算 P50、P95、P99、失败率和重试率。
- [ ] 进度事件需要可恢复、可去重，并能按 `workflowId` 查询完整历史。
- [ ] 增加预计剩余时间；在没有可靠估计时显示“正在等待模型/无法估计”，不能伪造百分比。

当前实现位置：

- `frontend/src/app/components/MultiAgentWritingPanel.tsx`
- `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingTaskEventStreamService.java`

### H6. 独立审校 Agent 未形成闭环

- [ ] 产品确认四阶段拓扑是否长期保持，明确速度、成本和质量的取舍。
- [ ] 若要求一流水平质量，至少恢复一个独立内容审校阶段和一个学生安全审校阶段。
- [ ] 对复杂数学题增加答案、公式、题目条件和讲解之间的一致性复核。
- [ ] 将审校失败、自动修复和最终放行分别记录，不能只保留最终 PDF 状态。
- [ ] 布局审校至少覆盖跨页、悬空线段、立体几何实虚线、文字重叠、图片遮挡和学生版信息泄漏。

当前主要是模型生成后由 Java 导出层、PDF 编译和版式门禁进行检查，缺少完整的独立审校闭环。

### H7. Token 控制仍然偏软

- [ ] 为 `resource_curation`、`teacher_writer`、`student_writer`、`lecture_writer` 分别配置上下文预算和输出预算。
- [ ] 在发送 Provider 请求前计算上下文规模，超限时按优先级淘汰重复资料、低相关资料和冗余指令。
- [ ] JSON repair 不应无条件重新发送完整上下文，应只发送必要的结构、错误位置和最小修复上下文。
- [ ] 为 retry 和 repair 设置独立次数、Token 和成本上限。
- [ ] 建立长输入样本和复杂题样本，验证 Token 上限不是只对普通样本有效。

真实 Trace 曾达到：

| 运行 | Prompt Token | Completion Token | Total Token |
|---|---:|---:|---:|
| `181546Z` | 61,798 | 11,408 | 73,206 |
| `185006Z` | 45,200 | 7,652 | 52,852 |
| `191753Z` | 44,517 | 10,434 | 54,951 |

### H8. 两条讲义链路缺少统一治理

- [ ] 明确主教学 DAG 和多智能体写作链路的产品边界。
- [ ] 统一 workflow、stage、Token、成本、质量门禁、事件和产物状态模型。
- [ ] 统一模型选择、预算、重试和降级策略。
- [ ] 统一验收报告，避免一条链路的指标无法与另一条链路比较。
- [ ] 在没有统一治理前，文档必须明确两条链路的差异，避免运维误判。

涉及主要实现：

- `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`

## 5. P2：持续治理项

- [ ] 拆分约 1300 行的 `TeachingWorkflowService`，按检索、编排、渲染、质量、导出和状态持久化拆分职责。
- [ ] 收敛分散的默认值、并发数、预算、timeout 和 lease 配置，避免多个入口产生不一致。
- [ ] 将阶段 Token、成本、耗时从 JSON metadata 中拆出可查询的明细表或指标流。
- [ ] 建立自动化成本、时延、质量 Dashboard。
- [ ] 统一真实 XeLaTeX 与测试环境，减少 fake-xelatex、Noto CJK 和 Win32 可执行文件差异。
- [ ] 降低测试数据与异步时序的耦合，增加确定性 fixture 和状态等待条件。
- [ ] 将全量测试失败分类治理，不得只依赖定向测试结果宣称整体通过。

## 6. 当前测试与验收状态

根据 `docs/change-log-2026-08-03.md`：

- Java 21 真实编译成功。
- 四阶段多智能体定向测试：37 tests、0 failures、0 errors、1 skipped。
- 前端 Vitest：21 个测试文件、102 个测试通过。
- 前端 `tsc && vite build` 成功，仅有 bundle 大小提示。
- 已完成多轮真实 MCP、PDF、图片、权限和 MySQL/Redis/Milvus/ai-worker 验收。
- 全量后端真实 `mvn test`：756 tests、52 failures、15 errors、8 skipped。

全量测试不能标记为通过。已知失败集中在 Windows 中文编码、fake-xelatex/真实 XeLaTeX 与 Noto CJK 环境差异、旧教师检索排序断言，以及测试数据和异步时序耦合。

## 7. 达到一流水平的验收指标

### 成本

- [ ] 每次 workflow 都能查询真实实际成本。
- [ ] 预算超限时模型调用被可靠阻断。
- [ ] Retry、repair 和降级调用全部计入预算。
- [ ] 估算成本与实际成本的误差有统计口径，并持续监控。

### 时延

- [ ] 有 queue、model、retry、render、export、total 的 P50/P95/P99。
- [ ] 最长请求、lease 和 retry 时间关系经过自动化验证。
- [ ] 尾延迟超过阈值时能够压缩、降级或停止，而不是无限等待。

### 一致性

- [ ] 多实例并发更新不会覆盖已完成阶段。
- [ ] 重复消费不会重复生成或重复计费。
- [ ] 每个阶段具备幂等键和可恢复状态。

### 质量

- [ ] 内容审校、安全审校和版式审校形成闭环。
- [ ] 教师版、学生版、16:10 版均有独立发布门禁。
- [ ] 质量失败不会产生看似成功的 PDF。

### 进度

- [ ] 用户可看到真实阶段、重试、Token、成本和阻塞原因。
- [ ] 进度事件可恢复、可去重、可查询。
- [ ] 预计剩余时间没有可靠依据时不伪造精确数值。

## 8. 相关源码与文档

- `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunPlanService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/AgentRunExecutionService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/SpringAiOpenAiCompatibleGateway.java`
- `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowExecutionSupport.java`
- `backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingTaskEventStreamService.java`
- `frontend/src/app/components/MultiAgentWritingPanel.tsx`
- `docs/mcp-math-pdf-production-acceptance.md`
- `docs/handout-pdf-rendering-development-standard.md`
- `docs/handout-prompt-profiles-and-style-acceptance.md`
- `docs/workflow-token-latency-optimization-2026-07-28.md`
- `docs/change-log-2026-08-03.md`
- `TODO.md`

最后更新：2026-08-03（Asia/Shanghai）
