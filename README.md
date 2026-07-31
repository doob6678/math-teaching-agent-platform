# 高中数学教学 Agent 平台

这是一个面向高中数学教学场景设计并实现的 AI 教学 Agent 平台。项目围绕教师端、学生端和管理端联动展开，把教材检索、教师资料解析、题库导入、知识图谱、学生学习画像、教学任务编排、模型治理、权限限流与审计串成一条可落地的教学业务链路。

平台以 Java 后端为核心，基于 Spring Boot 3.5、Java 21、Spring AI、MyBatis-Plus、MySQL 和 Redis 搭建 Agent/RAG 服务分层。工程结构按 controller、service、dto、vo、mapper 拆分，业务模块之间保持清晰边界，同时为前端控制台、外部 MCP/A2A 集成和后台资料处理提供统一接口。

## 核心对标：成熟高中数学教师式讲解

项目把“豆包爱学”截图中的教学态度作为讲义与单题讲解的质量基线，而不是把“模型返回了文字”当作完成：

1. 按“题型识别 → 方法梳理 → 分步推理 → 总结回顾”组织数学内容。
2. 解释概念、方法选择和每一步为什么成立，设置理解检查、追问和 `<wait>` 课堂停顿，不只给答案。
3. 用清晰板书顺序呈现题目、公式、计算和结论；先准确术语、后口语化解释，兼顾严谨、考点、评分点与常见误区。
4. 通过知识图谱说明知识点归属、先修关系、关联方法、学习阶段和难度；处理顺序是“扫描题目 → 匹配图谱 → 按知识点与思想方法组织”。
5. 推理遵循“目标 → 相关知识 → 已知条件 → 逻辑推导”，并受真实证据约束，禁止虚构题目、来源、定理条件、图形关系和数值答案。

多 Agent 流程分别生成并审校教师讲义、无答案学生空白讲义、无最终答案的 16:10 单题课堂引导稿。页眉、页脚、页码、字体和纸张比例由 PDF 渲染器控制，不写入模型正文；提示词档案与样例见 [讲义提示词档案与样式验收](docs/handout-prompt-profiles-and-style-acceptance.md)。

## 讲义与 PDF 不可违反的硬性要求

以下要求是讲义链路的开发契约，不得以“模型已返回文本”替代真实验收：

涉及讲义、公式、PDF、页眉页脚、学生作答区或 16:10 版的任何开发，**必须先阅读并遵守**[讲义 PDF 渲染开发规范](docs/handout-pdf-rendering-development-standard.md)。

1. 写作前必须分别检索教材与已授权教师/飞书资料；教师讲义的每道原题均须可读地注明教材章节与资料来源，禁止只保留内部 ID。
2. 教师讲义至少包含 6 道连续编号、独立陈述的原题；检索证据不足时必须明确失败，禁止编造题目凑数。
3. 公式必须处于 `$...$` 或 `$$...$$` 数学环境。分式只能使用 `\frac{分子}{分母}`，根式只能使用 `\sqrt{完整被开方内容}`；禁止用 `/`、`／`、`√` 或不带花括号的根式替代结构。导出器会拒绝含歧义公式的文档并回报行号。
4. 学生版的计算、证明和说明题必须给垂直留白；只有单值填空可用短横线。学生版和 16:10 版不得泄露最终答案、教师批注、trace 或来源内部标识。
5. 16:10 版仅含一个题目，题干位于首个正文区块，页面比例为 16:10，且文本密度受审计门禁约束。
6. PDF 必须由 Docker 内 XeLaTeX 与 Noto CJK 字体真实编译；验收必须在 Windows 渲染全部页面，保存截图、布局审计、SHA-256、HTTP 状态、trace、阶段耗时和 token 消耗。任何一项失败均不得标记验收完成。

## 工程结构

| 目录 | 说明 |
|---|---|
| `backend-java/` | Spring Boot 3.5 + Java 21 后端，承载业务接口、Agent 编排、RAG 检索、数据库持久化、安全策略和协议服务。 |
| `frontend/` | 配套前端控制台，覆盖多页面导航（工作台、教材检索、教学任务、Agent 编排、流式编排、知识库、系统设置、独立登录页）。 |
| `文档/` | 产品方案、工程设计、开发进度、资料位置和交付记录。 |

## 后端模块

后端主入口在 `backend-java/`，核心模块包括：

| 模块 | 说明 |
|---|---|
| `resources` / `retrieval` | 教材 catalog、chunk 读取、BM25-first 检索、页面质量降权、缓存和检索审计。 |
| `teacher` | 教师资料源登记、飞书同步 checkpoint/resume、资料块解析、检索和审计。 |
| `knowledge` | 题库导入、知识点、知识关系、题目与知识点绑定。 |
| `student` / `memory` | 学生学习画像、学习快照、知识图谱组装和学生记忆复用。 |
| `teaching` | 可恢复教学任务 DAG/ReAct 编排、人工反馈、讲义导出、阶段耗时统计和**异步执行（CREATED/RUNNING/COMPLETED/FAILED 状态机）**。 |
| `agent` | Agent 执行计划、模型调用、Trace 持久化、恢复查询和多 Agent 写作 workflow。 |
| `protocol` | MCP 工具发现、MCP 工具执行和 A2A Agent Card 元数据。 |
| `securityrisk` / `infrastructure.security` | Capability Token、请求哈希、角色/API 分级、Redis 限流、防重放和审计。 |
| `infrastructure.ai` | 模型 provider/model 目录、健康检查、fallback 策略和密钥脱敏。 |

## 教材与 RAG 检索

教材检索链路采用 BM25-first 方案，从外部教材目录读取 `catalog.jsonl` 和分块内容。检索服务会保留教材、章节、页码、片段、公式和图片引用，并结合页面质量标签对低价值页面降权。每次检索生成 `queryId`，写入审计事件，支持按 `queryId` 查询命中详情，为 RAG 回答提供可追溯的教材证据。

教师资料检索与教材检索保持一致的证据结构。飞书同步支持 checkpoint/resume，本地 md、txt、docx、pdf 等资料解析后按 block、checksum、citation 入库，后续可以从教师资源块中识别数学题、去重并绑定知识点，沉淀为教师侧题库资产。

## 知识图谱与学习画像

知识图谱模块提供**SVG 力导向图可视化**，基于力导向布局算法（排斥力 + 吸引力 + 向心力）自动排列知识点节点。主干模块以彩色矩形标识（12 色自动分配），知识点为蓝色圆形，高频方法为紫色圆形。图谱支持拖拽节点、拖拽平移、滚轮缩放、悬停高亮关联节点和关系、图例展示等交互。

学生端可以看到知识节点、知识边、薄弱风险、历史问题和学习进度；教师端可以结合教材、飞书资料和教师自有资料证据做备课分析。知识图谱不是孤立展示层，而是与题库、资料检索、学生画像和教学任务编排共同工作。

## 教学任务与 Agent 编排

教学任务采用可恢复 DAG/ReAct 编排，支持学生记忆复用、阶段耗时统计、并发保护、human feedback、Agent Trace 持久化与任务恢复。**教学任务已实现异步化**：提交时立即返回 `CREATED` 状态，通过 `multiAgentWritingTaskExecutor`（core=2, max=4, queue=32）在后台执行，前端每 2 秒轮询进度。任务状态机完整落地：`CREATED → RUNNING → COMPLETED/FAILED`，失败时携带 errorMessage。

多 Agent 写作 workflow 在此基础上扩展，支持异步启动、MyBatis 持久化、前端状态面板恢复和 MCP Trace 读取。教师可以围绕讲义、学生版材料、审稿反馈等角色组织多 Agent 协作，后台保留可查询的 workflow 状态和执行链路。

## 分布式 Agent Worker

多 Agent 写作支持控制面与 Worker 分离运行：控制面仅校验权限、保存 workflow 和释放满足依赖的阶段；独立 Java Worker 通过 RabbitMQ 领取阶段任务，执行后回写 Trace 和阶段结果。Worker 节点登记、心跳、离线判定、租约回收、重试和 DLQ 均由 MySQL 与 RabbitMQ 协同实现。

任务消息只传任务/工作流/阶段引用，不传用户令牌、模型密钥或原始提示词。启动方式、环境变量、状态机与恢复流程见 [Agent Worker 架构文档](docs/agent-worker-architecture.md)。

## 模型治理与安全

模型治理层维护 provider/model allow-list、fallback 顺序、真实连通性探测和密钥脱敏，前端只读取后端暴露的模型目录与健康状态，不接触 API Key。

高价值 AI 接口使用 Capability Token、`X-Request-Hash`、角色/API 分级、Redis 固定窗口限流、一次性令牌消费与审计记录保护，降低高成本接口被盗刷、重放和越权调用的风险。MCP/A2A 暴露也遵循只读优先、范围可控、密钥不回显的原则。

## 教师资料同步 MQ

教师资料同步是当前最适合消息化的业务：一次操作可能下载飞书资料、解析 DOCX/PDF、调用 Python 视觉 Worker，并重建 Milvus 索引。接口先完成 Capability Token 校验，再仅把 `jobId`、`documentId` 与后端解析出的主体身份发布到 RabbitMQ；令牌、API Key 和请求哈希不会进入消息。

RabbitMQ 使用持久化 direct exchange、命令队列和死信队列。MySQL `source_sync_job` 仍是状态机和幂等锚点：消费者只执行 `queued`（或恢复时 `paused`）任务，重复投递不会重复解析同一资料；业务/提供方失败继续使用已有 checkpoint/pause 机制，只有格式或基础设施异常进入 DLQ。默认并发为 1、预取为 1，避免单机预占过多 CUDA/解析任务；这些值固定在 `application.yml`，启动时不接受环境变量覆盖。

## 前端设计

前端采用 **React 19 + Vite 7 + TypeScript 5**，使用 Outfit/DM Sans 字体家族。设计风格以深海军蓝导航 + 暖金琥珀色点缀 + 清爽灰白内容区为主。

### 多页面导航

| 页面 | 功能 |
|---|---|
| 工作台 | 学生学习概览、教材资源统计、快捷操作入口 |
| 教材检索 | BM25+向量混合检索、命中证据、审计追踪 |
| 教学任务 | 教学任务创建（异步提交 + 轮询等待）、讲义导出、反馈 |
| Agent 编排 | Agent Policy 配置、模型规划执行、追踪面板 |
| 流式编排 | 多智能体协作写作工作流 |
| 知识库 | SVG 力导向知识图谱可视化、知识点维护、题库管理、向量 RAG 检索 |
| 系统设置 | MCP 配置、后端连接、教师资源管理、当前会话状态 |
| 登录页 | 独立登录页面（手动输入账号密码），登录后自动跳转工作台 |

### 核心特性

- **API 超时控制**：30 秒超时（AbortController），超时抛出明确错误
- **指数退避重试**：网络失败时自动重试（1s → 2s → max 4s + jitter）
- **UUID 幂等**：所有 clientRequestId 使用 `crypto.randomUUID()`
- **统一表单体系**：所有 input/select/textarea/button 统一使用 CSS 类体系
- **登录引导**：未登录用户访问受限页面时显示引导卡片，点击跳转登录页

### 知识图谱可视化

知识图谱使用纯 SVG 力导向图实现（无外部依赖）：
- **力导向布局**：200 次迭代的弹簧模型，含排斥力、吸引力、向心力
- **节点类型**：模块（彩色矩形）、知识点（蓝色圆形）、方法（紫色圆形）
- **交互**：拖拽节点、拖拽平移、滚轮缩放（ZoomIn/ZoomOut）、悬停高亮关联
- **图例**：显示所有模块颜色 + 节点类型标识
- **工具提示**：悬停显示节点详情、关联关系、路径

## 运行入口

完整的后端拆分、环境变量来源、aisz provider、资源格式、端到端流程、MySQL/Milvus 实时快照和验收产物见
[后端拆分与运行手册](docs/backend-split-operations.md)。本地 Docker 入口固定为
`http://127.0.0.1:5173/`，后端为 `http://127.0.0.1:8080`，Python worker 为 `http://127.0.0.1:8092`。

## 2024 高考数学题库：真实视觉入库链路

本项目对配置文件 `config/gaokao-ingestion-2024.json` 中限定的六份 2024 北京、新课标Ⅰ、新课标Ⅱ PDF，使用唯一的真实入库命令：

```powershell
wsl.exe -d Ubuntu -- python3 /mnt/c/Users/doob/Desktop/code/dev/math_agent_rag/scripts/wsl/run_2024_luna_milvus_ingestion.py
```

该命令严格按以下顺序完成，任何一步失败都会以非零退出，不能产出“成功”结论：

1. 以生产后端相同版本的 PDFBox 渲染每个真实 PDF 页，保留原始 PNG；再生成最长边 960px、JPEG quality 0.82 的初筛页图。
2. 每页仅将压缩页图发送给 `gpt-5.6-luna`，识别题干、LaTex 公式、题号和跨页风险；原始 PNG 仍作为可复核证据。
3. 在 `output/gaokao-evidence/2024/runs/<run-id>/` 保存每页完整的非密钥请求、响应、页图 SHA-256、HTTP 状态、耗时及 provider 返回的 token usage。Authorization 不写入文件。
4. 仅将 Luna 返回的非空题干和公式调用本机运行的真实 embedding worker，写入统一的高考 Milvus collection `gaokao_math`。
5. 使用刚入库题干重新生成真实查询向量并从 Milvus 召回；若查不到对应的插入主键，整次运行失败。最终报告写入 `output/gaokao-evidence/2024/<run-id>-report.json`，其中汇总 `prompt_tokens`、`completion_tokens`、`total_tokens`。

高考题统一使用 `gaokao_math` collection；之后任何高考题导入不得新建按年份或模型拆分的 collection。AI 并发固定为 20；`--page-workers` 只能降低本次脚本进程的并发，不能把脚本线程数表述为全项目额度。后端 agent 路径的分布式额度由 Redis 租约控制；若要把外部批量脚本纳入同一额度，必须经后端入库 API 申请租约，而不是并行执行多个脚本。每页审计额外记录实际 worker 数、任务序号、线程名、开始/完成时间，保证并发时仍能对一张页图、一份请求/响应和一笔 token usage 一一追溯。Luna 请求通过 `--luna-bridge-container` 指定的健康 Docker worker 网络发出，容器名不是代码常量；使用 `docker compose ps -q ai-worker` 获取当前部署的容器 ID。单次 HTTP 默认限时 120 秒，并由父进程额外 5 秒宽限的硬超时兜底；520、429 和临时 5xx 只对该页请求进行局部、有界的最多 3 次重试。每个 run 都写入 source/config 哈希 manifest，`--finalize-run-id` 会重新验证 PDF、原图和压缩图哈希后才允许写入。跨页题仅在前页明确标记续页且后页没有新题号时合并；LaTeX 分式含斜杠会被拒绝，必须为 `\\frac{分子}{分母}`。向量化固定调用本机真实 ai-worker 的 `local_bge_embedding`（512 维）；Milvus 会确保 `gaokao_math` 上存在 `COSINE`/`FLAT` 的 `vector_index` 后才加载并写入。链路不会扫描配置之外的 2024 PDF，不使用文本层伪造视觉识别结果，不把模型输出当作官方答案或人工审核结论。启动只允许设置 `OPENAI_API_KEY`；其余端口、数据库、Milvus、worker、embedding 与并发配置均固定在 YAML。

`math-agent.agent-worker.runtime.max-concurrency` 固定为 `20`，表示全项目同时允许执行的 AI 工作单元总数，而不是单个 PDF 的页数。该值不接受环境变量覆盖。

Luna 题目识别的公式契约要求：任何可见数学分数在 `latex` 字段中必须写成 `\\frac{分子}{分母}`；严禁使用 `1/2`、`a/b`、`x/y` 等斜杠记法代替分式。该约束同时写入 2024 页图识别提示词及后端已授权页图转写提示词，确保上传链路与批量链路一致。

### Final local startup contract

The Compose deployment is intentionally single-configuration: do not set ports, database, Milvus, embedding,
worker, retry, or concurrency environment variables. Their fixed values live in `docker-compose.yml` and
`backend-java/src/main/resources/application.yml`: frontend `5173`, backend `8080`, worker `8092`, MySQL `3307`,
Redis `6380`, Milvus `19531`, `gaokao_math`, `local_bge_embedding` (512 dimensions), and AI concurrency `20`.
The only permitted runtime environment variable is `OPENAI_API_KEY`.

```powershell
$env:OPENAI_API_KEY = "your-provider-key"
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/c/Users/doob/Desktop/code/dev/math_agent_rag && docker compose up -d --build"
```

After the stack is healthy, open `http://127.0.0.1:5173`. Do not use the obsolete `5174` or `8081` ports.

2024 视觉入库会读取这一个全局值并据此创建最多相同数量的页任务；其最终报告的 `concurrency.globalLimit` 与 `concurrency.effectivePageWorkers`、以及每页审计的 `taskSequence`、`workerThread`、`taskStartedAt`、`taskCompletedAt` 共同证明没有绕开全局控制。

后端服务：

```powershell
cd backend-java
mvn spring-boot:run
```

配套前端：

```powershell
cd frontend
npm install
npm run dev
```

教材资源目录由 `docker-compose.yml` 固定只读挂载到 `/app/data/textbooks`，后端配置在
`backend-java/src/main/resources/application.yml` 固定引用该容器路径。除 `OPENAI_API_KEY` 外，启动不读取
任何端口、数据库、Milvus、模型、并发或外部资源环境变量。
