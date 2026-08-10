# Python LangGraph 讲义链路改造说明

## 目标与边界

本次改造把“需要模型判断和生成”的部分收敛到 Python LangGraph，把确定性和安全边界留在 Java：

```text
Java API/Auth/Tenant/MySQL
        |
        | RabbitMQ opaque task + Java lease/ACK
        v
Java workflow row (runId)
        |
        | Worker 持有一个 lease 的长生命周期 HTTP 请求
        v
Python LangGraph
  resource_curation
       |
  teacher_writer + student_writer + lecture_writer (并行)
       |
  structured_validation -> bounded repair
        |
        v
Java publication gate -> LaTeX/XeLaTeX -> PDF/SSE
```

Python 不拥有 Java 的身份、证据权限、Redis/Milvus 检索、业务 workflow 或资产存储；它只用 `runId` 调用 Java 的 `handout-context`，Java 从已持久化 workflow 反查真实 `RequestSubject` 后返回压缩证据。模型 Provider 凭证由 worker 容器通过环境变量注入，不能从请求体传入；Python 只向 MySQL 写入受控的不可变 `ai_usage_event` 账本，不执行业务查询或资产读写。生产环境要求 usage 写入失败时图失败，禁止在账本缺失的情况下 ACK。

## 当前实现清单

- `ai-worker-python/app/handout_runtime.py`
  - LangGraph 四阶段 DAG；三路 Writer 从同一 evidence snapshot 并行执行。
  - Pydantic 结构化契约：`WriterDocument`、`ValidationReport`、`HandoutDraftPackage`。
  - JSON 外围说明、代码围栏、lecture card/resource card、`<wait>`、投影横线和填空线先由代码清洗；只有清洗后仍然结构或语义不合法时才最多一次 repair，普通路径仍是资源整理 + 三次 Writer 模型调用。
  - 通过 `MATH_AGENT_HANDOUT_MAX_TOTAL_TOKENS`、`MATH_AGENT_HANDOUT_MAX_PROVIDER_CALLS` 和 deadline 在 Provider 请求前限制 retry/repair 预算；超过预算直接失败，不扩大上下文或无限重试。
- checkpoint/event store 支持两种后端：生产 Compose 默认 `MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND=mysql`，通过共享
  MySQL 行锁合并并发 Writer 的 checkpoint；单进程测试可显式使用 `sqlite` 和 `/app/data/handout-checkpoints.sqlite3`。
  两种后端都提供 `afterId` 游标，重启和 SSE 断线只补发缺失事件。
  - 连接池、节点耗时、Java 往返次数/字节数、provider 成功/失败、token、估算成本、CPU/RSS/GPU 采样。
- `ai-worker-python/app/server.py`
  - `POST /v1/handout-runs/sync`：Java 生产主链路使用的一次性长请求。
- `POST /v1/handout-runs`：后台提交一次 Graph，SSE 只读取共享 event store 游标；不会在浏览器请求线程重复执行模型。
- `GET /v1/handout-runs/{runId}/events?afterId=&limit=`：恢复和验收审计，返回 `eventId/nextAfterId`。
- Java
  - `PythonHandoutClient`：复用 RestClient，一次 HTTP 调用完成 Python Graph。
  - `AgentToolBrokerController#handoutContext`：由 `runId` 反查 workflow 权限，不信任 Python 发送的身份字段。
  - `MultiAgentWritingService`：在 `math-agent.python-handout.enabled=true` 时将同步、异步、恢复入口切换到 Python；现有 StageResult、artifact、PDF 接口保持兼容。
- Compose
  - Python 使用 `http://backend:8080` broker 地址和现有共享 key。
  - backend 使用 `http://ai-worker:8091`，RabbitMQ 的 `PythonHandoutAgent/python_handout` 路由承载完整 Python Graph；未迁移任务继续使用原有路由。

## 跨语言协议

Java 到 Python 的请求只允许以下字段：

| 字段 | 作用 | 约束 |
|---|---|---|
| `runId` | Java workflow 主键，同时是 checkpoint/thread key | 8-80 字符 |
| `taskId` | 业务任务关联 | 仅标识符 |
| `writingGoal` | 讲义目标 | 1200 字符内 |
| `questionText` | Java 已规范化的题目批次 | 16000 字符内 |
| `evidenceRefs` | 证据锚点 | 最多 24 条，每条 240 字符 |
| `graphVersion` | 图版本/回滚标识 | 例如 `handout-v1` |
| `traceId`、`deadlineEpochMs` | 追踪和超时 | 不携带身份 |

Python 到 Java 的 `handout-context` 仅发送 `runId/query/evidenceRefs/limit`。Java 返回最多 12 个证据项，每项最多 3000 字符，并只保留 `documentId:blockId`、标题、摘录和 opaque `assetId`。

## RabbitMQ 粒度

迁移入口不再为 `resource_curation`、三个 Writer 分别建 AMQP 任务；一个 Python handout task 只拥有一个 Java lease，Worker 在 lease 内调用一次 Python Graph。这样减少 4 次消息确认、4 次数据库租约往返和 4 次跨服务序列化，同时保留：

- 顶层排队、削峰、prefetch、consumer 并发；
- Java lease heartbeat、失败重试和 DLQ；
- Python checkpoint 对节点边界的恢复能力。

`AgentWorkerTaskConsumer` 对 `python_handout` 只 ACK 一次，且 ACK 发生在 Python 结果写回 MySQL workflow 后；重复 RabbitMQ 投递先经过 MySQL lease/CAS，不会重复发布。`math-agent.python-handout.enabled=false` 可恢复旧 stage 编排。

## 性能和成本控制

1. Java Worker 每次队列任务只调用 Python 一次；Python 只调用 Java 一次批量证据接口。
2. 三个 Writer 共享同一个 evidence snapshot，避免每个 Writer 重新检索和重复发送原始 OCR。
3. 传输证据使用 ref/摘要/assetId，不使用 Base64 原图和本地路径；图片由 Java 最终资产授权读取。
4. Python 使用长生命周期 HTTP session；事件按节点写入，不逐 token 写 MySQL。
5. 常规路径是三次并行 Writer 模型调用，repair 只有结构化失败才触发；resource curation 使用一次 Java 批量检索，不再额外调用模型。provider usage 缺失时明确标记 `fallback`，不伪造供应商账单；价格未配置时返回 `costKnown=false`/`-1`，生产仍必须写入 usage 账本。
6. 本地 embedding/rerank/CLIP 默认 CUDA；未配置 GPU 时服务状态应明确为不可用，不能静默退回 CPU。

验收时对每个 `runId` 保存：请求/响应字节数、HTTP 状态、每节点 elapsed、provider/model、prompt/completion/total tokens、估算成本/成本未知标志、Java 请求次数、RabbitMQ queue wait、lease wait、ACK 延迟、PDF XeLaTeX 时间、PDF SHA-256、CPU/RSS/GPU 采样和失败原因。

### RabbitMQ 与恢复时序

```text
HTTP startAsync
  -> MySQL workflow=RUNNING
  -> MySQL agent_worker_task(stage=python_handout,status=QUEUED)
  -> publish opaque task id
  -> Worker claim lease
  -> Python Graph(resource + 3 writers + validation)
  -> MySQL workflow/artifact/usage 写入成功
  -> Worker complete task
  -> RabbitMQ ACK
```

Worker 重投递或用户 resume 使用同一个 `runId` 和 `resume=true`。Python 从 SQLite checkpoint 恢复已完成的 `resource_curation`、`teacher_writer`、`student_writer` 或 `lecture_writer` 节点；节点恢复只返回已验证 artifact，不重新打开模型连接。最终 validation 仍会重新执行，任何缺题、乱序、空内容、投影禁用内容或学生答案泄漏都会拒绝发布。

## 上线清单

### 配置

- [ ] `.env` 中已有 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_CHAT_MODEL=gpt-5.6-luna`、Worker key 和 Broker key 已存在。
- [ ] Compose 的 `ai-worker`、`backend`、MySQL、Redis、RabbitMQ、Milvus health 均为 healthy。
- [ ] `MATH_AGENT_PYTHON_HANDOUT_ENABLED=true` 只在 Python worker 可用且 Java broker key 匹配后开启。
- [ ] Python checkpoint 路径位于持久 volume；不要把 checkpoint 写到容器临时层。
- [ ] GPU 容器使用 `gpus: all`，模型路径挂载为只读，设备值为 `cuda`。

### 功能与安全

- [ ] 真实 teacher/admin 请求只能从 Java session 解析 subject，不能从请求 body 选择 tenant。
- [ ] `handout-context` 的 `runId` 必须能在 Java workflow 表中找到；找不到返回 404。
- [ ] 学生版本、教师版本、课堂投影版本分别检查；学生版本不能出现答案、评分点和教师笔记。
- [ ] Java publication gate 通过后才允许 LaTeX/XeLaTeX 和 PDF 存储；Python 返回的 Markdown 不是发布授权。
- [ ] 资产只以 opaque assetId 传递，原始路径和 storage key 不进入 prompt、checkpoint 或日志。

### 故障恢复

- [ ] 重启 Python 后带 `resume=true` 的请求读取 SQLite checkpoint，不重复完成节点。
- [ ] RabbitMQ 重复投递只会被 Java lease/CAS 拦截，不会导致重复发布。
- [ ] Python provider 失败、Java broker 失败、结构化校验失败均写入失败事件和安全错误摘要。
- [ ] 关闭 Python handout 开关后，旧 Java stage worker 可正常完成回滚任务。

## 已知限制与后续演进

- Python 的 checkpoint 表仍是项目自有契约，不把 LangGraph 内部 serializer 当作业务状态真相；RabbitMQ 的跨进程可靠性由 Java task lease/CAS 负责。
- Java `/api/teaching/tasks/{taskId}/events` 继续读取 Java 业务快照；Python event cursor 只用于 Worker/内部桥接，不能绕过 Java 的租户权限和发布门禁。
- Java 现有旧 Agent broker 路由为兼容路径，新的讲义 Graph 不再使用它们的身份字段；后续可在所有 AgentRun 完成迁移后统一改为 `runId` 派生权限。

## 架构审查结论（2026-08-04）

### 应当迁移到 Python 的 AI 边界

| 能力 | 归属 | 原因 |
|---|---|---|
| 资源语义整理、证据选择 | Python Graph | 需要模型判断、循环和可恢复节点；只接收 Java 已授权摘要 |
| 教师版/学生版/16:10 Writer | Python Graph | 三路独立生成可以并行，Pydantic 合同统一输出 |
| 结构化解析、受众规则和题目顺序门禁 | Python | 与模型输出紧邻，能在 repair 前确定性拒绝错误结果 |
| 模型路由、重试、usage/token 统计 | Python | 避免 Java 为每个 provider 维护重复 SDK 和协议适配 |
| 登录、租户、权限、MySQL workflow | Java | 是业务和安全最终真相，不能由模型服务绕过 |
| RabbitMQ、lease、ACK、DLQ | Java | 是生产任务调度能力，不等同于 LangGraph checkpoint |
| Milvus/教师资料授权检索 | Java broker | Python 只能按 `runId` 获取权限过滤后的最小证据快照 |
| assetId 授权、LaTeX/XeLaTeX、PDF/SSE | Java | 确定性高风险发布边界，保留路径限制和资源权限 |

### 当前架构已经解决的问题

1. **跨语言调用次数**：一次 Graph 只做一次 Java `handout-context` 请求，三路 Writer 共享快照；没有为每个 Agent 单独检索、序列化和鉴权。
2. **RabbitMQ 粒度**：一个队列任务拥有一次 Java lease，内部完成整个 Python Graph，避免四个阶段分别 ACK、续租和落库。
3. **状态真相**：Java workflow row 是业务状态和权限真相；Python checkpoint 只记录 AI 节点执行边界。Java 只接收 `HandoutDraftPackage` 并重新执行发布门禁。
4. **恢复语义**：`resume=true` 会加载 evidence 和已经验证的 writer，已完成节点返回 `RESUMED`，未完成节点才重新调用 provider；失败时保留最近 checkpoint，不覆盖已完成兄弟节点。
5. **输出质量**：模型输出先走对象优先 JSON 解析，再做受众、题目顺序、非空和禁用内容校验；只有校验失败才做一次最小 repair。题目门禁要求每题至少一个独特 token 按顺序命中，避免把自然改写误判为漏题。

### 仍需优先修复或补强的问题

| 优先级 | 问题 | 影响 | 建议验收标准 |
|---|---|---|---|
| P0 | 共享 checkpoint 必须和业务 workflow 分开 | 多副本 resume 时不能依赖容器本地文件 | Compose 默认 MySQL；SQLite 仅单进程开发；双 worker 以同一 `runId` 行锁合并 |
| P0 | Java `RestClient` 必须显式 connect/read timeout 和 deadline 传递 | Python provider 卡住时可能占用 Java lease | 客户端预算、Python deadline、RabbitMQ lease 三者取最小值并留安全余量 |
| P0 | SSE 不能在请求线程执行 Graph | 浏览器重连会触发重复模型调用 | 后台提交 + event store 游标，断线用 `afterId` 补发 |
| P1 | RabbitMQ queue wait、lease wait、ACK latency 尚未纳入同一份 run metrics | 只能看模型耗时，不能算端到端 P95/P99 | 记录 enqueue/claim/start/complete/ack 时间戳并按队列、租户、模型聚合 |
| P1 | provider 价格未配置时 `costKnown=false` | 不能把 token 直接换算成货币成本 | 为 `provider/model` 配价格版本和生效时间，报告 input/output/cached token 成本 |
| P1 | Java broker 返回的证据响应字节数尚未进入 Python metrics | 无法完整比较压缩前后网络成本 | 增加 `javaResponseBytes`，同时记录证据条目数和字符数 |
| P1 | 学生安全审校、独立内容审校仍主要是确定性门禁 | 复杂题的答案泄漏或事实错误可能漏过 | 增加独立审校节点，失败只重跑对应 Writer，不重跑已完成兄弟节点 |
| P2 | 当前 `POST /v1/handout-runs` 仍在请求线程执行 Graph | 长任务受 HTTP 代理和连接池限制 | 生产入口走 RabbitMQ；SSE 只读取 event store，不承担模型执行生命周期 |

### 尽可能减少 Java/Python 通信耗时的实施顺序

1. **保持单次批量 context**：Java 端一次返回最多 12 条压缩证据，Python 三路复用；禁止 Writer 自己回调 Java。
2. **压缩协议**：只发送 `runId/taskId/writingGoal/questionText/evidenceRefs`，证据只保留 ref、标题、excerpt、assetId；禁止图片 Base64、路径和完整 OCR 重复进入三路 prompt。
3. **连接复用**：Java `RestClient` 和 Python `requests.Session` 都使用长生命周期连接池；生产环境配置明确连接和读取超时。
4. **任务内聚**：RabbitMQ 只传 opaque taskId，Java lease 内完成一整个 Graph，避免每个阶段的消息序列化和数据库往返。
5. **事件批量化**：节点完成才写 event/checkpoint，不写 token delta；Java SSE 以 event id 游标读取，断线只补发缺失事件。
6. **按失败节点恢复**：修复或重试只带错误字段和题目，不重新发送完整 evidence；已完成 Writer 从 checkpoint 直接复用。
7. **最后再考虑同进程**：只有确认队列等待占总耗时很小、并发低且可接受简化恢复时，才在开发环境用进程内 Graph；生产仍保留 RabbitMQ。

## 真实验收记录

验收目录：`output/acceptance/python-langgraph-handout/run-real-luna-20260804-final/`。

输入为 4 道连续题（定义域、基本不等式最值、二次函数参数最值、正方体线面角），通过真实 Java broker 获取教材证据，并使用真实 `gpt-5.6-luna`。最终结果：

| 指标 | 实测值 |
|---|---:|
| HTTP | 200 |
| Graph 状态 | `COMPLETED` |
| 端到端耗时 | 70,092 ms |
| Java context 请求 | 1 次，request payload 505 bytes |
| Writer provider 调用 | 3 次成功，0 次失败，0 次 repair |
| Prompt / completion / total tokens | 25,818 / 9,210 / 35,028 |
| Writer 节点耗时 | student 41,671 ms；lecture 62,985 ms；teacher 68,610 ms |
| checkpoint/event | started → resource → 三路 writer → validated → completed |
| Windows RSS | 84,992,000 → 93,216,768 bytes |
| GPU 采样 | GPU 0：2620 MiB / 8151 MiB；利用率采样为 0（模型为远端 provider） |
| 价格 | `costKnown=false`，未伪造货币成本 |

说明：三路 Writer 的最大耗时 68.6 秒接近 Graph 总耗时 70.1 秒，证明并行生效；如果串行执行，三路模型耗时会叠加到约 173 秒以上。验收响应、Java broker 响应、HTTP metadata 和最终事件分别保存在上述目录，便于复现和审计。

## 改造任务清单

### 已完成

- [x] Python LangGraph 资源整理、三路 Writer、结构化验证和有限 repair。
- [x] Java `runId` 权限 broker；Python 不直连 MySQL、Redis、Milvus、文件和资产存储。
- [x] Java/Python 单次 Graph 协议、连接复用、RabbitMQ 顶层任务和 Java lease/ACK。
- [x] SQLite checkpoint/event、失败保留最新状态、`resume=true` 跳过已完成节点。
- [x] 对象优先 JSON 解析，兼容 provider 返回数组，过滤资源/证据卡和内部字段。
- [x] Python/Java 回归测试和真实 Luna 验收。

### 上线前必须完成

- [ ] Java RestClient 显式超时、Graph deadline 和 RabbitMQ lease deadline 联动。
- [x] checkpoint 迁移到共享 MySQL store（保留 SQLite 单进程开发后端），并完成同一 `runId` 的并发状态合并探针。
- [x] Python SSE 改为后台执行 + event store 实时游标；Java 业务 SSE 仍只读 Java 权限快照。
- [x] Java PythonHandoutClient 显式配置连接/读取超时，且 deadline 早于 RabbitMQ lease 到期。
- [ ] 接入 queue/lease/ACK/PDF 编译时延、P95/P99、重试率、DLQ 指标。
- [ ] 接入 provider 价格版本、cached token、按租户成本上限和超预算停止策略。
- [ ] 增加独立答案正确性审校、学生安全审校和题目-证据绑定审计。
- [ ] 完成三种 PDF 版本的真实 XeLaTeX、PNG、分页和图片权限验收；Python Markdown 不能直接发布。

### 回滚开关

```text
MATH_AGENT_PYTHON_HANDOUT_ENABLED=false
```

关闭后 Java 恢复原有 stage worker 路径；RabbitMQ、MySQL、PDF 和既有业务 API 不需要切换数据格式。回滚演练必须确认已完成的 Python workflow 仍可读取、导出和审计，未完成任务由旧路径重新领取而不是删除记录。
