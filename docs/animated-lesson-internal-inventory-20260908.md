# 一题一课·分步动画讲解 — 仓库内部资产盘点（只读调研）

日期：2026-09-08。方式：只读源码/文件盘点，未修改任何代码、未启动服务。
目的：为「给一道题 → AI 生成分步动画讲解视频（图形逐笔绘制、辅助线逐条出现、每步中文讲解、章节时间戳、结构化解析）」找出可复用资产与集成点。

---

## 1. tools/geo-draw 几何配图智能体（最核心可复用资产）

目录：`C:\Users\doob\Desktop\code\dev\math_agent_rag\tools\geo-draw\`

### 端到端流程（`geo_draw_agent.py`，186 行）

1. **输入**：UTF-8 题干文本文件（示例 `problem.txt`）。CLI 入口（L133-140）：
   `python geo_draw_agent.py problem.txt --out final.png --dpi 600 [--rounds 3] [--skip-review]`
2. **模型调用**（L60-78 `chat()`）：OpenAI 兼容 `/chat/completions`，纯 `requests`，无 SDK。
   - 网关地址：`OPENAI_BASE_URL`（当前值 `https://api1.aisz.mom/v1`）
   - 模型名：`OPENAI_CHAT_MODEL`（当前值 `gpt-5.6-terra`，即 Terra）
   - 密钥来源：**项目根 `.env` 的 `OPENAI_API_KEY`**（`C:\Users\doob\Desktop\code\dev\math_agent_rag\.env`，L45-57 `load_env()` 先取进程环境变量、缺失回退读根 `.env`；密钥明文不在本文抄录）
   - 视觉审阅用**同一模型**带图调用（L88-95 `review()`，图片以 base64 image_url 附在最后一条 user 消息，L63-69）——注意这是工具本地行为，讲义链路仍禁止向 AI 传图片二进制。
3. **TikZ 生成与编译修复循环**（L149-162）：
   - 系统提示词（L30-37）强约束"坐标必须由几何约束推导（\pgfmathsetmacro 旋转/交点/等长），禁止目测坐标"，输出 standalone TikZ 围栏块；
   - 编译在 `render.py` 的 `compile_tex()`（L20-37）：**xelatex**（MiKTeX），`-interaction=nonstopmode -halt-on-error`，编译前删旧 PDF 防静默复用（实测坑，L23 注释）；
   - 失败时把 xelatex 日志尾部 40 行回喂对话让模型自修（L160-161），≤`--rounds` 轮——与讲义 LaTeX 修复循环同模式。
4. **VLM 审阅打分**（L39-40 REVIEW_PROMPT、L88-95、L167-174）：编译成功后 `pdf_to_png` 300dpi 渲染，交给同一 Terra 模型对照题干输出 `{"pass": bool, "issues": [...]}`（无分数，布尔+意见清单）；不通过把 issues 回喂重修。实测记录见 README L31-32：一轮即过，产物 `final.png` 7705×3275px；Terra 单次调用约 8 分钟，整轮约 16 分钟（网关延迟）。
5. **输出与落盘**（L111-130 `compose_final`）：最终把中文题干（`sanitize_stem` 把 ∠/△ 等 Unicode 符号换 LaTeX 命令，L98-108）与矢量配图 PDF 合成一页 standalone 文档，`final_page.tex` 落在本目录，`--dpi`（默认 600）栅格化为 PNG。中间产物：`agent_figure.tex` / `agent_figure.build/agent_figure.pdf` / `agent_figure_review.png`。
6. **被谁调用**：全仓 grep（`geo_draw|geo-draw`，排除本目录）无任何调用方——**目前是独立 CLI 原型，尚未接入服务链路**。README L78-87「接入讲义链路的建议」为未实施设计稿：AI Writer 输出 ```tikz 围栏块 → worker 同款 render 管线 → 产物按 assetId/evidenceRef 入库。

### 对动画功能的复用点

- **逐笔绘制的天然素材**：TikZ 源码本身是"先定基线再逐点推导"的构造序列（draw 语句按构造顺序排列），把每步 draw/animate 拆开即得分步动画帧序列。
- `render.py` 的 `compile_tex`+`pdf_to_png`（L20-46）是**确定性的 Tex→PNG 管线**，每步编译出一帧即可，供 ffmpeg 合成。
- 编译修复循环 + VLM 审阅循环（`geo_draw_agent.py` L149-176）可整体移植为"动画分镜脚本生成"的质检环。
- 本机依赖已核实齐备（见第 7 节）。

---

## 2. ai-worker-python 编排与新增 workload 注册点

目录：`ai-worker-python\app\`（FastAPI，`server.py` 593 行）

### 编排模式（两条，按任务时长选）

**A. 同步/流式 HTTP（Java 直调 Python，学生讲解走这条）**
- Java 签发 `runId` 与 HMAC route grant：`backend-java\...\agent\service\ProviderRouteGrantSigner.java` L26 `sign(runId, workload, routes)`；
- Python 验证 grant 绑定 runId+workload：`ai_run_runtime.py` L50-59 `ProviderRoute.verify_for()`（workload 白名单校验，防跨运行复用）；
- Java 调 Python 客户端：`backend-java\...\agent\service\PythonMigratedWorkloadClient.java`（L73 recognizeIntent / L92 transcribeImage / L123-240 streamStudentExplanation* / L463 composeStudentExplanation / L466 providerHealth），每处 `providerRoute(runId, "<workload>")` 即 workload 名出现点（L81、97、123、168、240、375、430、457、466）；
- SSE 流式：`workload_runtime.py` L329-449 `stream_student_explanation`（started/delta/completed/error 事件，reasoning 与 content 分道上抛）；`student_explanation_runtime.py` L181-335 `DurableStudentExplanationRuntime` 提供 run 级幂等（request_fingerprint）、RUNNING/COMPLETED/FAILED 终态缓存、事件表重放（`student_explanation_checkpoint` + `student_explanation_event`，MySQL 或 SQLite 后端，L27-57），delta 80ms 窗口合并（L185、L284-303）。断线重连按 `Last-Event-ID` 游标读事件不再烧 provider 预算（`server.py` L336-376）。

**B. 异步队列（讲义走这条，重任务适用）**
- `HandoutTaskFacade.startAsync` → `AgentWorkerTaskDispatchService.create(workflow, agentCode, stageCode, requestJson)` 落 `agent_worker_task` + outbox → `AgentWorkerTaskOutboxPublisher/Scheduler` 投 RabbitMQ `agent.worker` exchange → `AgentWorkerTaskConsumer`（L68 @RabbitListener）领取，租约 CAS QUEUED→RUNNING→COMPLETED/FAILED（详见第 5 节）。`AgentWorkerRabbitConfiguration.java` L25 `PYTHON_HANDOUT_STAGE_CODE = "python_handout"` 是"该阶段转发 Python 执行"的路由标记。

### 节点图（LangGraph，供多阶段动画生成参考）

`handout_runtime.py`（2941 行）L1402-1413：`graph.add_node("resource_curation"/"teacher_resource_curation"/"plan_writer"/...)` + 条件边；节点注册即在此。若"动画课程生成"要走多阶段（审题→分镜→逐帧 TikZ→编译→合成→审阅），这是最接近的模板。

### 新增一种 workload（如 animated_lesson）的注册点清单

1. **Python 合同**：`workload_runtime.py` 仿 `StudentExplanationRunRequest`（L138-166）新建 Pydantic 合同，`providerRoute.verify_for(runId, "animated_lesson")`；或复用通用 `AiRunRequest` 需扩 `ai_run_runtime.py` L115 `workload: Literal["generic_agent"]` 与 L30 ProviderSelection 不变；
2. **Python 路由**：`server.py` 仿 L330/L336/L364 加 `@app.post("/v1/animated-lessons/sync|stream|events", dependencies=[Depends(require_worker_key)])`，L179-202 处 `@lru_cache` runtime 工厂注册（`migrated_workload_runtime`/`durable_student_explanation_runtime` 同款）；
3. **Java 调用与签发**：`PythonMigratedWorkloadClient` 加方法 + `providerRoute(runId, "animated_lesson")`；grant 字符串 workload 名同步；重异步任务则新 `stageCode` 走 `agent_worker_task`；
4. **图片/产物回传**：走既有资产流程（assetId/evidenceRef），渲染器不选图不改图（AGENTS.md 边界）。geo-draw 若要 GPU 无关，可像 `latex_repair_runtime.py`（L101-176，`/v1/latex-repair/sync`，server.py L397）那样把外部进程编译闭环包成 Python 端点。

### anthropic_compat.py（378 行）提供什么

它**不是 Anthropic 家的模型访问**，而是"Anthropic Messages 线格式 ↔ OpenAI chat-completions 形状"的传输层桥（L1-19 模块注释）：GLM（智谱 `https://api.z.ai/api/anthropic`，`ANTHROPIC_FORMAT_PROVIDERS = {"glm"}` L33）走 Anthropic 兼容端点，本模块把上层构造的 OpenAI payload 翻成 `/v1/messages`（`build_messages_payload` L78，含 system 提升、tool_calls 映射、多模态 image 块映射 L116-136、强制 thinking 低档 L160-161），再把响应/流式帧翻回 OpenAI 形状（`to_openai_completion` L189、`openai_sse_data_lines` L332、`post_chat_completion`/`post_streaming` L344-378；reasoning→`reasoning_content` 隔离不进正文）。provider 档案（名称→密钥 env→base_url→线格式）在 `provider_profiles.py` L60-75：openai(Terra 网关)/dashscope/deepseek/ark/glm 五家，密钥分别是 `OPENAI_API_KEY`、`DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`、`ARK_API_KEY`、`GLM_API_KEY`（值在根 `.env`/环境，不抄录）。

---

## 3. 题库数据（高考真题语料）

- 位置：`output\math-paper-corpus\`，12 套目录（目录名 = 完整来源 PDF 文件名），如 `2022年高考数学试卷（新高考Ⅰ卷）（解析卷）.pdf\`。
- 每套结构（已实读验证）：`source-manifest.json` + `document.md`（全文）+ `questions\q-NNN.md`（逐题）+ `figures\q-NNN-0X.png`（题图）+ `page-images\page-NNN.png`（整页审计产物，禁止进讲义）。
- manifest 顶层键：`documentFullName, sourceSha256, authoritativeTranscription("TERRA_VISUAL_PAGE"), documentMarkdown, questionCount, pageCount, pages[]（pageNo/canonicalAssetPath/assetId/assetSha256）, questions[]（questionNumber/questionId/questionMarkdown/questionMarkdownSha256/sourcePages/crossPageContinuity/assetIds/assets[]）`——**题目级资产绑定是 assetId+sha256，不是路径**。
- 题目 Markdown 结构：标题行（来源+题号）→ 元信息（来源页/跨页）→ 题干 → 转写 LaTeX 行 → `【答案】` → `【解析】`（分析/详解/图引用 `![第 N 题图](figures/q-NNN-0X.png)`）。全部 248 个 md 中有 figures 引用的约 66 题（分布 12 套卷）。
- **中考类题库：仓库内没有**。只有高考 corpus。

### 适合先做分步动画原型题（带真实题图，摘录自文件）

1. **两圆公切线（最佳：辅助线逐条出现）**
   `output\math-paper-corpus\2022年高考数学试卷（新高考Ⅰ卷）（解析卷）.pdf\questions\q-014.md`（图 `figures\q-014-01.png`）
   题干摘录（L7）：「写出与圆 x²+y²=1 和 (x-3)²+(y-4)²=16 都相切的一条直线的方程」。
   答案（L9）：`y=-3/4 x+5/4` 或 `y=7/24 x-25/24` 或 `x=-1`。解析自带"连心线→切线 l/m/n 分情况"三步，正好对应分镜。
2. **圆外两点切线夹角（隐形三角形+切线长，辅助线驱动）**
   `output\math-paper-corpus\2023年高考数学试卷（新课标Ⅰ卷）（解析卷）.pdf\questions\q-006.md`（图 `figures\q-006-01.png`，L70）
   题干摘录（L7）：「过点 (0,-2) 与圆 x²+y²−4x−1=0 相切的两条直线的夹角为 α，则 sin α=」；答案（L14）：**B（√15/4）**。解析给出配圆心、切点 A/B、连 PC 的作图顺序。
3. **椭圆+动直线恒成立（图形逐笔+直线旋转演示）**
   `output\math-paper-corpus\2024年高考数学试卷（天津）（解析卷）.pdf\questions\q-018.md`（图 `figures\q-018-01.png`，L27）
   题干摘录（L7）：「已知椭圆 x²/a²+y²/b²=1(a>b>0) 离心率 e=1/2，左顶点 A、下顶点 B，C 是 OB 中点，S△ABC=3√3/2…（2）过点 (0,-3/2) 的直线与椭圆交于 P、Q，y 轴上是否存在 T 使 TP·TQ≤0 恒成立」。
   答案（L15-17）：`(1) x²/12+y²/9=1；(2) 存在 T(0,t)，-3≤t≤3/2`。
4. **椭圆焦点三角形周长（定义法，对称折叠动画）**
   `output\math-paper-corpus\2022年高考数学试卷（新高考Ⅰ卷）（解析卷）.pdf\questions\q-016.md`（图 `figures\q-016-01.png`，L36）
   题干摘录（L7）：「椭圆 C 上顶点 A、两焦点 F₁/F₂，离心率 1/2，过 F₁ 且垂直 AF₂ 的直线交 C 于 D、E，|DE|=6，求 △ADE 周长」；答案：13（4a 定义转化）。
5. **曲线轨迹图（丝带曲线 C，函数图像逐段绘制）**
   `output\math-paper-corpus\2024年高考数学试卷（新课标Ⅰ卷）（解析卷）.pdf\questions\q-011.md`（图 `figures\q-011-01.png`，L20）
   题干摘录（L7）：「曲线 C 过原点，C 上的点到 F(2,0) 距离与到定直线 x=a(a<0) 距离之和为 4」（抛物线定义变形）；答案：ABD。

### 「圆切线最值/带辅助线」的类题在教师资料里

教师资料库（宿主路径，`.env` `MATH_AGENT_LOCAL_TEACHER_RESOURCES_HOST_ROOT` → `C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\飞书中间下载_md\高中数学\`）：
- `直线与圆\看到直角三角形就要想到圆.md`——"隐形圆确定轨迹"专题（苏锡常镇一模），飞书图链，切线/轨迹最值类；
- `直线与圆\TODO 阿波罗尼斯圆 （阿氏圆）.md`——PA=kPB 轨迹圆方程推导（带完整 LaTeX），正是"圆上动点最值+辅助线"题材；
- `直线与圆\TODO 直线与圆的位置关系.md` 仅 16 行大纲（TODO 未填充）；
- 另有 `解析几何\圆锥曲线的切线.md`、`导数\导数切线问题\计算两个函数公切线.md`、`平面几何\三角形性质\`（中线/面积 TODO）。
结论：**教师库是笔记态（多为 TODO 壳、图片走飞书外链未入库），不能当题源，只能当分镜风格参考**；正式题源仍是 corpus 12 套。宿主上还有手工 TikZ 素材库 `C:\Users\doob\Desktop\个人资料\高中数学\latex画图\`（直线和圆/圆锥曲线/二面角/球/auxil 等 `.tex+.pdf` 示意图，非程序）。

---

## 4. 前端（React + Vite，无路由库，单 App 状态机）

- 目录：`frontend\src\`；入口 `app\App.tsx`（4665 行）。页面是 `type PageId`（L198：dashboard|search|teaching|agents|streaming|knowledge|mcp|settings|login）+ `navItems`（L2503-2510：工作台/教材检索/AI 讲题/AI 控制台/讲义生成/知识库/MCP/设置），`navigate()` L2487 切页。依赖极简：react 19 + katex 0.17 + pdfjs-dist 6 + lucide-react（`frontend\package.json` L12-21）。
- **学生讲解页（AI 讲题）**：`app\components\TeachingConversationPanel.tsx`（1410 行）。渲染方式：POST-SSE 流式——API 在 `shared\api\textbookApi.ts` L2885-2920（fetch + ReadableStream 手解 SSE 帧）、L3629-3645 `streamStudentQuestion → /api/students/explanations/stream`；增量进 `liveContent`/`liveThinking`（线程项类型 L28-58），打字机队列 L576 自适应排空；终稿卡片 `ExplanationCard`（L905）按 `renderMode: text|formula|source_list` 渲染。**公式渲染非 react-katex，是自研**：`App.tsx` L178 `MathText`（`katex.renderToString` + splitMathText 按 $...$ 分段，L1016 同款逻辑在面板内 `InlineMathText`/`RichText` L958-990）。
- **视频组件：不存在**。全 frontend/src grep `video|<video|player` 零命中；无 hls/播放器依赖。需要新写 `<video>` 壳（MP4 直链即可，nginx 静态服务现成 `frontend\nginx.conf`）。
- **可当"视频+章节侧栏+结构化解析"骨架的现成布局**：
  1. 讲义工作台：`App.tsx` streaming 页 = `HandoutHistorySidebar`（左侧历史列表，`components\HandoutHistorySidebar.tsx`）+ `HandoutWorkspacePreviewPanel`（右侧双 tab 预览：LaTeX 源码 / PDF，`components\HandoutWorkspacePreviewPanel.tsx` L97-110，内部 `PdfCanvasPreview.tsx` 用 pdfjs 画布渲染 Uint8Array PDF）——**左列表+右预览面板的分栏骨架直接可改造为 章节侧栏+播放器**；
  2. 讲解卡片流：`TeachingConversationPanel` 的 cards 序列（cardKey/title/summary/items/sourceUris）即"结构化解析卡片"模式，已知条件/解题目标/核心观察/答案四类卡可作为约定 cardKey 复用同一渲染器；
  3. 思考流直播 UI（折叠思考行 L443-446 注释）可复用于"生成中分镜日志"。

---

## 5. Java backend：任务持久化与下发复用

- **agent_worker_task 机制（推荐复用，重异步任务）**
  表：`backend-java\src\main\resources\db\migration\V22__agent_worker_node_and_task.sql`——`agent_worker_node`（worker 注册/心跳/负载/支持 agent 列表）+ `agent_worker_task`（task_id, workflow_id→FK multi_agent_writing_workflow, tenant_id, agent_code, stage_code, status, attempt, lease_token/lease_expires_at, worker_id, request_json）；`V36__agent_worker_task_outbox.sql`——`dispatch_version` 幂等列 + `agent_worker_task_outbox_event`（PENDING→发布，(task_id,dispatch_version) 唯一，重试/租约列）。
  状态机：QUEUED→(租约 CAS) RUNNING→COMPLETED / FAILED(超限入 DLQ)，租约过期由 `AgentWorkerLeaseRecovery` 回收重投（架构文档 `docs\agent-worker-architecture.md` L13-17；消费者 `agent\worker\AgentWorkerTaskConsumer.java` L68-140，其中 L140 按 `PYTHON_HANDOUT_STAGE_CODE` 把阶段转给 Python 执行）。
  下发链：`agent\worker\AgentWorkerTaskDispatchService.java` L31-50 `create(workflow, agentCode, stageCode, requestJson)`（任务+首条 outbox 同事务）→ `AgentWorkerTaskOutboxPublisher/Scheduler` 投 RabbitMQ（`AgentWorkerRabbitConfiguration`）。
  复用方式：新增 stage_code（如 `animated_lesson`），workflow 记录可挂现有 `multi_agent_writing_workflow` 或仿 V37 建新 run 表；**不建议**为动画任务扩 `agent_code` 到 courseware 队列语义。
- **StudentExplanationWorkflow 机制（轻量同步，不适合长任务）**
  表：`V37__student_explanation_workflow_run.sql`——`student_explanation_workflow_run`（run_id 主键, tenant/subject, client_request_id, request_fingerprint, status RUNNING/COMPLETED/FAILED, retry_count, deadline_at, response_json, error_*）+ `student_explanation_workflow_event`（游标事件表）。实现：`student\service\MyBatisStudentExplanationWorkflowStore.java` L59/107/116 状态写点；SSE 中继 `student\controller\StudentExplanationController.java`（L236 `/api/students/explanations/stream` SseEmitter 5 分钟，L343 `replayUntilTerminal` 断线重放，Redis pub/sub 网关 `StringRedisStudentExplanationWorkflowEventGateway`）。**动画任务是分钟-小时级，走 A 类同步 SSE 会超时，应走 worker task 队列 + 完成后产物入库、前端拉静态文件。**
- 另有教学任务同款 outbox：`teaching\entity\LectureTaskOutboxEventEntity.java`（第三处重复该模式，证明模式稳定）。

---

## 6. 既有相关尝试（避免重复造轮子）

- **manim：全仓零引用**（grep -i manim 无命中）；未安装。
- **视频**：唯一既有代码 `tools\browser-recording\record_demo.py`——Playwright `record_video_dir` 录 webm + **ffmpeg 转 mp4 命令行**（文件头 L7-10 注释：`ffmpeg -i bing_demo.webm -c:v libx264 -pix_fmt yuv420p ...`），证明本机 ffmpeg 路线已验证过。
- **"动画"**：仅 `docs\android-app-ui-design-brief.md` 的 UI 动效规格，无关。
- **"一题一课"**：仓库零命中。相近命名文档 `docs\教学-逐课台账.md` 是讲义课时台账，非视频。
- **逐帧渲染基建（重要发现）**：`tools\latex-studio\`（README 实读）——真实 xelatex「智能遍数编译 + 日志解析 + PyMuPDF 逐页 PNG」核心在 `compiler.py`（服务与 CLI 共用），`review_cli.py` 一条命令出逐页 PNG 供 AI 读图质检；`server.py` 常驻 http://127.0.0.1:8764 支持 AI 推送式直播（每产出一段 tex 就 POST 秒级出稿）。**"每步一帧 tex → 编译 → PNG"的动画帧生成可完全复用 compiler.py，不必新写编译层。**
- 讲义 LaTeX 修复循环（`ai-worker-python\app\latex_repair_runtime.py`，端点 server.py L397 `/v1/latex-repair/sync`）是已上线的"编译错误回喂模型重写"闭环，与 geo-draw 本地循环同思想，动画脚本自修复可挂靠。

---

## 7. 本机环境（Windows，只读探测）

| 项 | 结果 |
|---|---|
| Python | 3.12.12（`D:\conda\envs\py_12\python.exe`，Git Bash 默认） |
| manim | **未安装**（`pip show manim` 无） |
| ffmpeg | **已装** `C:\Users\doob\Tools\ffmpeg\bin\ffmpeg.exe`（在 PATH） |
| xelatex | **已装** MiKTeX `C:\Users\doob\AppData\Local\Programs\MiKTeX\miktex\bin\x64\xelatex.exe` |
| PyMuPDF | 1.27.2.3 |
| matplotlib / sympy | 3.10.8 / 1.14.0（geo-draw README 提到的纯 Python 备选路线可用） |
| cairosvg | 未装 |

WSL/Docker 服务未触碰（只读盘点要求）。

---

## 8. 复用结论速览（原型最短路径）

1. 分镜生成：复用 geo-draw 的 prompt+修复+VLM 审阅循环（改造为"每步输出增量 TikZ 层"），模型走既有 provider 档案（Terra/openai 网关，密钥在根 `.env`）。
2. 逐帧渲染：复用 `tools\latex-studio\compiler.py` / `tools\geo-draw\render.py`（xelatex+PyMuPDF 高 DPI PNG 帧）。
3. 合成视频：ffmpeg 帧序列→mp4（本机已装；webm→mp4 用法已在 browser-recording 验证）。
4. 任务编排：新 stage 走 `agent_worker_task` + outbox（重异步），结果事件仿 `student_explanation_workflow_event`；Python 端注册点见第 2 节清单。
5. 题源：第 3 节 5 道 corpus 题（含 assetId 绑定题图，符合授权链）。
6. 前端：改 `HandoutHistorySidebar + HandoutWorkspacePreviewPanel` 分栏骨架为"视频 + 章节侧栏"，卡片沿用 `ExplanationCard`/`MathText`。
