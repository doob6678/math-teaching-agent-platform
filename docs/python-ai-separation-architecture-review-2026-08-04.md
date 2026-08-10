# Python AI 解耦与讲义链路架构审查

审查日期：2026-08-04  
审查对象：`math_agent_rag` 当前 Java 后端、Python Worker、RabbitMQ、MySQL/Redis/Milvus 和讲义导出链路。

## 结论先行

推荐采用下面的边界，而不是“把 Java 全部重写成 Python”或“删除 RabbitMQ”：

```text
浏览器 / OpenAPI
        |
        v
Java 控制面：登录、租户、权限、任务、证据授权、MySQL 业务状态、RabbitMQ lease/ACK/DLQ
        |
        | opaque runId + 规范化题目 + evidenceRefs，一次批量 context
        v
Python AI 执行面：LangGraph、资源整理、三路 Writer、结构化校验、有限 repair、模型路由与 usage
        |
        | HandoutDraftPackage（版本化、无路径、无身份）
        v
Java 发布面：答案/学生安全门禁、资产授权、LaTeX/XeLaTeX、PDF、SSE 与审计
```

一句话原则：

> Python 可以拥有模型判断和 AI 工作流，但不能拥有租户权限、原始文件、业务状态或 PDF 发布权限。

## 当前代码已经做到什么

### 已落地的 Python AI 边界

- `resource_curation` 从 Java broker 获取一次已授权的证据快照。
- `teacher_writer`、`student_writer`、`lecture_writer` 从同一快照并行执行，互不重复检索。
- Pydantic 合同、对象优先 JSON 解析、受众安全规则、题目顺序检查、投影过滤和一次有界 repair 均在 Python。
- Python 不接受 `tenantId`、身份、文件路径、数据库连接、原图或 provider key；证据权限由 Java 按 `runId` 反查。
- Java 仍执行工作流状态写回、资产授权、LaTeX 安全检查、XeLaTeX 编译和 PDF 发布。
- RabbitMQ 的粒度是一个完整 Python handout task；内部 LangGraph 节点不分别建队列、不分别 ACK。

### 已修复的跨语言可靠性问题

- Python checkpoint/event 支持 `sqlite` 和共享 `mysql` 两种后端。Compose 默认 MySQL，SQLite 仅用于单进程开发和单元测试。
- MySQL save 使用 `SELECT ... FOR UPDATE` 合并 evidence 和并行 Writer，避免兄弟节点后写覆盖先写。
- SSE 入口是“后台提交一次 Graph + event store 游标读取”，重连使用 `afterId`，不会因浏览器重连再次打开模型调用。
- Java `PythonHandoutClient` 使用复用的 JDK HTTP 连接池，并显式设置 connect/read timeout。
- Python deadline、Java HTTP budget 和 RabbitMQ lease 取最小值，Java 留出 lease safety margin，避免旧 Worker 在重投递后迟到写回。

## 现有架构的主要问题

### 1. Java 教学 DAG 仍保留 AI 草稿实现

`TeachingWorkflowExecutionSupport` 仍直接调用 `TeachingAiDraftService`，其中包含 provider 调用、JSON repair、图片压缩和较长 prompt。当前 Python LangGraph 主要覆盖 `/api/agents/writing/courseware` 的讲义路径，`/api/teaching/tasks` 仍有一套 Java AI runtime。

这会导致：

- provider 超时、重试和 token 统计存在两套实现；
- 讲义接口和教学任务接口的质量门禁可能产生不同结果；
- 同一 GPU/供应商并发额度无法统一计算；
- Java 仍需维护图片 Base64、provider DTO 和 JSON 结构修复。

处理策略：先把 Java `TeachingAiDraftService` 改为 Facade，调用 Python `teaching-drafts` 合同；保留 Java 旧实现作为显式回滚开关，确认真实验收后删除 provider 代码。

### 2. Java 业务状态和 Python 图状态必须继续分层

Java `workflow/status/revision` 是业务真相；Python `thread/checkpoint/event` 只表示 AI 节点边界。不能让两边都写“任务完成”并互相推断。

正确状态转换是：

```text
Java CREATED -> RUNNING -> Python package returned -> Java validation/publication -> COMPLETED
                                  |
                                  +-> FAILED（保留 Python checkpoint，允许 resume）
```

Python 返回 `COMPLETED` 不等于 PDF 已发布。Java 发布门禁失败时，必须保持 Java workflow 未发布，且 resume 只重跑缺失/失败节点。

### 3. 当前 Java SSE 与 Python SSE 的职责不同

Python `/v1/handout-runs/{runId}/events` 是内部 Worker 事件源，只能返回无 prompt 的操作事件。浏览器仍访问 Java `/api/teaching/tasks/{taskId}/events`，Java 按 session subject 读取业务快照。

不要让浏览器直接拿 Python worker key，也不要把 token delta 写入 MySQL 业务表。Java 后续可订阅 Python event cursor，把节点状态映射成受权限过滤的教学进度。

## 目标 DTO 与版本策略

所有跨语言 DTO 都应带 `contractVersion` 或通过 URL 版本化；新增字段必须向后兼容，删除字段只能在 graph version 升级后进行。

### `HandoutAiRunRequest`

```json
{
  "contractVersion": "handout-ai-v1",
  "runId": "java-workflow-id",
  "taskId": "business-task-id",
  "writingGoal": "bounded string",
  "questionText": "Java normalized question batch",
  "evidenceRefs": ["opaque:ref"],
  "graphVersion": "handout-v1",
  "traceId": "trace-id",
  "deadlineEpochMs": 0,
  "resume": true
}
```

禁止字段：`tenantId`、`subjectId`、`filePath`、`assetPath`、`apiKey`、SQL、原图 Base64。

### `EvidenceSnapshot`

只允许 `ref/title/excerpt/assetId`。`excerpt` 和条目数由 Java 限制；asset 只能是 opaque id，Python 不能凭 id 自行访问存储。

### `HandoutDraftPackage`

必须包含：`runId`、`taskId`、`graphVersion`、`status`、三份 audience documents、`ValidationReport`、`AiUsage`、`AiFailure` 和节点指标。Java 只把它当草稿输入，不当发布授权。

## 如何把 `/api/teaching/tasks` 收敛成唯一业务主链

### 阶段 A：Facade 化

1. 保持 `LectureTaskSubmissionService` 作为唯一提交入口，继续使用 MySQL task + outbox 去重。
2. `TeachingWorkflowExecutionSupport` 只负责确定性检索、记忆复用、模板选择、发布门禁和 PDF。
3. 将 `TeachingAiDraftService.draft(...)` 改成 `TeachingAiDraftFacade`：Python 开关开启时只调用 Python；关闭时调用旧实现。
4. Python 返回与现有 `TeachingTaskResponse.AiDraft` 等价的版本化字段，Java 负责映射，不复制 Python checkpoint。

### 阶段 B：迁移 AI 细节

1. 把 Java prompt、provider fallback、JSON 修复、图片压缩、topic/question coverage 校验移到 Python。
2. Java 传送证据摘要和 assetId；Python 只在 prompt 中使用摘要，不读取原始 asset。
3. 学生安全审校、独立答案正确性审校和题目-证据绑定审计作为 Python 节点输出，Java 重新执行最低限度确定性门禁。
4. 旧 Java provider 调用保留一段灰度周期，禁止与 Python 双写或双调用同一个 run。

### 阶段 C：删除重复 runtime

迁移完成后，Java 只保留确定性业务调用；`SpringAiOpenAiCompatibleGateway` 仅服务尚未迁移的非讲义功能。所有讲义 provider usage 只从 Python `ai_usage_event` 账本读取。

## 降低 Java/Python 通信耗时的具体做法

### 请求次数

一次 handout run 的目标上限：

```text
Java Worker -> Python Graph：1 次长连接请求
Python resource_curation -> Java broker：1 次批量 context 请求
Writer -> Java：0 次
```

三路 Writer 共享同一个 evidence snapshot。不要为每个 Writer 重新检索、鉴权或发送 OCR。

### 字节数

传输成本应按以下式子监控：

```text
crossLanguageBytes = requestBytes + contextResponseBytes + packageResponseBytes
```

`requestBytes` 只含规范化题目和 ref；`contextResponseBytes` 只含最多 N 条摘要；`packageResponseBytes` 只在最终返回时发送，不发送 token delta、图片原文和本地路径。

### 连接与超时

- Java RestClient 使用长生命周期连接池；connect timeout 只负责建连，read timeout 受 graph deadline 限制。
- Python `requests.Session` 复用连接；Java broker 和 provider 都使用剩余 deadline 作为读取上限。
- 设 `effectiveDeadline = min(javaHttpBudget, pythonGraphBudget, rabbitLease - safetyMargin)`。
- provider 重试前必须重新计算剩余时间；没有剩余时间就失败并保留 checkpoint。

### 恢复与重试

- 队列重投递使用相同 `runId` 和 `resume=true`。
- 已完成节点从 MySQL checkpoint 返回 `RESUMED`，不重新打开 provider。
- 只对失败 Writer 做最小 repair；不要重跑已经成功的兄弟分支。
- Java ACK 必须发生在 workflow、artifact 和 usage 写入成功之后。

## 并发、GPU 和成本治理

需要一个统一的额度模型，不能分别让 Java、Python、脚本各自“认为自己没超限”：

```text
tenantLimit <= globalProviderLimit <= GPU/remote-provider capacity
```

至少记录：queue wait、lease wait、graph elapsed、每节点 elapsed、provider elapsed、ACK latency、P95/P99、重试率、DLQ 数、GPU memory、RSS、prompt/completion/cached tokens。

价格配置必须按 `provider/model/effectiveAt` 版本化。缺少价格时 `costKnown=false`，不能把 token 直接乘一个猜测值。

## 上线清单

### P0：上线前必须完成

- [x] Python LangGraph 资源整理、三路 Writer、结构化校验和有限 repair。
- [x] Java `runId` 权限 broker；Python 不直连 MySQL 业务表、Milvus、文件或资产原图。
- [x] 一个 RabbitMQ handout task 覆盖完整 Graph；Java lease/CAS/ACK/DLQ 保持不变。
- [x] 共享 MySQL checkpoint/event cursor；SQLite 只用于单进程开发。
- [x] Python SSE 后台提交和游标读取；断线可用 `afterId` 补发。
- [x] Java PythonHandoutClient connect/read timeout 与 graph deadline 联动。
- [ ] `/api/teaching/tasks` 的 AI 草稿改为 Python Facade，并完成旧 Java provider 路径灰度回滚演练。
- [ ] 双 worker 使用同一 runId 重投递：不重复模型调用、不重复发布、不丢兄弟 checkpoint。
- [ ] Python/Java/RabbitMQ/Provider 重启和断开演练通过。

### P1：生产观测与质量

- [ ] queue/lease/ACK/PDF latency 进入统一 metrics，支持租户、模型、队列维度聚合。
- [ ] 独立答案正确性审校、学生安全审校、题目-证据绑定审计。
- [ ] provider 价格版本、cached token、租户成本上限和超预算停止。
- [ ] 三种 PDF 版本真实 XeLaTeX、PNG、分页、图片权限验收；Python Markdown 不能直接下载。
- [ ] Java SSE 将 Python 节点事件映射为权限过滤后的业务进度。

### P2：完成迁移后的清理

- [ ] 删除讲义专用 Java prompt/provider/JSON repair 重复实现。
- [ ] 删除不再使用的旧 Agent 路由和 capability 字段，但保留数据库兼容期。
- [ ] 固定 Python/torch/CUDA 依赖和镜像缓存，干净构建不重新下载不可控的大包。

## 灰度、回滚和验收门槛

灰度开关：

```text
MATH_AGENT_PYTHON_HANDOUT_ENABLED=true
```

回滚只需关闭该开关；RabbitMQ、MySQL workflow、Java PDF 和旧 stage worker 格式不变。回滚不得删除 Python checkpoint，也不得把未发布草稿直接标记为已完成。

### 真实验收基线

已有真实 Luna 验收：4 道连续题，Graph `COMPLETED`，端到端约 70 秒，Java context 1 次（505 bytes），三路 Writer 并行，provider 3 次成功、0 次 repair。后续任何优化必须与这组基线比较：

- 单任务延迟：P50/P95/P99；
- Java/Python 请求次数和字节数；
- 三路 Writer 的最大耗时是否仍接近 Graph 总耗时；
- token、cached token、costKnown；
- 重启/重投递后的重复 provider call 数应为 0；
- 学生答案泄露、题目遗漏、证据错绑和 PDF 发布门禁失败均必须可审计。

## 最终判断

现在可以把“讲义 AI 编排”和“模型相关能力”继续迁移到 Python，但不应把 Java 的业务控制面、安全边界、任务调度或 PDF 发布一起搬走。RabbitMQ 解决的是生产任务生命周期，LangGraph 解决的是任务内部 AI 状态图；保留两者并明确边界，才能在减少跨语言往返的同时不牺牲恢复、削峰和审计能力。
