# 高中数学教学 Agent 前端功能、接口与 Stitch 生成提示词

> 基于当前仓库的 React 前端、`frontend/src/shared/api/textbookApi.ts` 和 `backend-python/src/math_agent/api` 真实代码整理。页面生成应优先复用现有接口；没有后端路由的能力标记为“待补接口”，不能在产品中伪装成已可用。

## 一、产品定位

这是一个面向高中数学教学闭环的高端工作台：

`学生学习 → AI 讲解 → 教材/资料证据 → 学习画像回写 → 教师诊断 → 练习/讲义生成 → Agent 追踪与治理`

核心体验不是普通聊天，而是“有证据、有过程、有版本、有权限”的数学教学操作系统。

## 二、整体信息架构

### 登录后按角色展示

| 角色 | 导航分组 | 页面 |
|---|---|---|
| 学生 | 学习空间 | 学习首页、AI 解题、知识进度、错题/练习、讲解历史 |
| 教师 | 教学空间 | 教师工作台、学生管理、学生画像、资料库、教材检索、练习管理、讲义工作台 |
| 管理员 | 系统治理 | 运营总览、知识库、题库、Agent 广场、Prompt/Skill、模型与额度、安全审计、运维 |
| 公共 | 账户 | 登录、个人设置、权限不足、系统状态 |

左侧使用可折叠深海军蓝侧栏；顶部显示当前空间、全局搜索、通知、系统健康、用户菜单。内容区为暖白背景、白色卡片、细金色强调线，避免彩色堆砌和大面积渐变。

## 三、已具备真实接口的页面

### 1. 学生学习首页 `/student/dashboard`

展示：今日学习摘要、掌握度环形图、薄弱知识点排行、最近题目、待复习提醒、最近 AI 讲解。

接口：

| 页面动作 | 接口 |
|---|---|
| 读取画像 | `GET /api/students/dashboard?studentId=` |
| 刷新画像 | `POST /api/students/dashboard/refresh?studentId=`，后端按登录用户主体校验并限流 |
| 写入学习记忆 | `POST /api/students/memory/remember` |
| 复用历史记忆 | `POST /api/students/memory/reuse` |

状态：首次加载骨架屏；刷新显示“正在重新计算学习画像”；错误时保留旧快照并显示更新时间。

### 2. AI 解题 `/student/solve`

布局：左侧题目输入与图片上传，中间分步讲解，右侧证据与学习反馈。题目图片上传后先展示 OCR/公式识别确认态，再允许提交。讲解区必须区分：结论、审题、知识点、方法、步骤、易错点、追问。

接口：

| 页面动作 | 接口 |
|---|---|
| 上传题图 | `POST /api/students/explanations/images` |
| 文本讲解 | `POST /api/students/explanations` |
| 流式讲解 | `POST /api/students/explanations/stream`，SSE |
| 历史消息 | `GET /api/students/explanations/history` |
| 会话列表 | `GET /api/students/explanations/conversations` |
| 会话详情 | `GET /api/students/explanations/conversations/{conversationId}` |

不可伪造：前端不自行计算掌握度、不自行声称模型用了某个 Skill；后端返回的 evidence、token、traceId 才是事实来源。

### 3. 教材检索 `/teacher/textbooks` 或公共检索页

布局：上方搜索框与筛选，中间结果列表，右侧证据抽屉/教材页面预览。每条结果展示教材、章节、页码、匹配分、摘要、公式/图片标识和 queryId。点击“查看原文”加载受控页面图片。

接口：`GET /api/resources/textbooks/summary`、`GET/POST /api/retrieval/textbooks/search`、`POST /api/retrieval/textbooks/page-search`、`GET /api/resources/textbooks/{docId}/pages/{pageNo}/image`、`GET /api/retrieval/audit/{queryId}`。

### 4. 教师资料库 `/teacher/resources`

三个入口：飞书发现、本地上传、已登记资料。资料表展示来源、权限范围、解析状态、索引状态、更新时间、块数和失败原因；详情页用 block 阅读器展示原文、图片资产和引用信息。

接口：`GET/POST /api/teacher/resources`、`POST /api/teacher/resources/upload`、`GET /api/teacher/resources/feishu/discovery`、`GET /api/teacher/resources/{documentId}/blocks`、`GET /api/teacher/resources/{documentId}/assets`、`GET /api/teacher/resources/assets/{assetId}`、`GET /api/teacher/resources/search`、`POST /api/teacher/resources/image-search`、`GET /api/teacher/resources/search/audit/{queryId}`、同步 job 的创建/执行/恢复/checkpoint 接口、`DELETE /api/teacher/resources/{documentId}`。

同步状态用时间线：登记 → 下载 → 解析 → 资料块 → 向量索引 → 可检索；失败节点提供重试和 checkpoint 恢复。

### 5. 知识库 `/admin/knowledge`

采用三栏：左侧知识点树，中间 SVG 知识图谱，右侧节点详情。节点类型必须有图例：章节模块、知识点、方法；支持拖拽、缩放、悬停关联高亮。下方切换题库列表和题目详情。

接口：`GET/POST /api/knowledge/points`、`GET/POST /api/knowledge/relations`、`GET /api/knowledge/graph/spine`、`GET/POST /api/question-bank/items`、`POST /api/question-bank/import/teacher-resources/{documentId}`。

### 6. 教学任务与讲义 `/teacher/teaching`

创建表单：年级、教材章节、知识点、教学目标、班级薄弱点、课时、版本、模板。提交后进入任务详情：任务状态、阶段时间线、实时事件、证据、人工反馈、教师版/学生版/讲解版切换、LaTeX/PDF 预览与导出。

接口：`GET /api/teaching/handout-templates`、`POST/GET /api/teaching/tasks`、`GET /api/teaching/tasks/{taskId}`、`POST /api/teaching/tasks/{taskId}/resume`、`GET /api/teaching/tasks/{taskId}/events`、`POST/GET /api/teaching/tasks/{taskId}/feedback`、讲义版本 PUT、LaTeX/PDF/preview 接口、批量 ZIP 创建与下载接口。

状态机只展示真实状态：`CREATED → RUNNING → COMPLETED / FAILED`。失败必须显示 errorMessage、traceId 和恢复入口。

### 7. Agent 广场与观测 `/admin/agents`

发现页以专业 Agent 卡片展示职责、输入输出契约、可读资料范围、工具范围、预计成本和权限标签；运行页先展示 Plan，再确认执行；详情页显示阶段 Trace、耗时、token、模型、证据和 artifact。

接口：`GET /api/agents/registry`、`GET /api/agents/model-catalog`、`GET /api/agents/model-health`、`POST /api/agents/run-plan`、`POST /api/agents/execute`、`POST /api/agents/knowledge-retrieval`、Trace 列表/详情/诊断/用量接口。

不展示原始 CoT；只展示可审计的阶段、工具调用摘要、输入输出 artifact 和错误信息。

### 8. 多智能体写作 `/teacher/multi-agent-writing`

以“任务输入 → 任务图 → 多 Agent 阶段 → 产物审校 → 导出”为主线，适配讲义、学生版、教师版和投屏版。支持异步启动、恢复、Trace、artifact 预览和导出。

接口：`POST /api/agents/writing`、`POST /api/agents/writing/async`、`GET /api/agents/writing/{workflowId}`、`GET /api/agents/writing/{workflowId}/traces`、`GET /api/agents/writing/{workflowId}/artifact`、`GET /api/agents/writing/{workflowId}/artifact/export`、`POST /api/agents/writing/{workflowId}/resume`。

### 9. MCP 与系统设置 `/settings`

展示后端连接状态、运行时、向量索引状态、MCP 工具清单、MCP key 管理、当前会话和 capability 审计。secret 只在创建成功时显示一次，列表页不得回显。

接口：`GET /api/mcp/tools`、`POST /api/mcp/tools/{toolName}/call`、`GET /api/mcp/keys`、`POST /api/mcp/keys`、`POST /api/mcp/keys/{keyId}/revoke`、`GET /api/mcp/configuration/me`、`GET /api/a2a/.well-known/agent-card.json`、`GET /api/vector-index/status`、运行时状态接口。

## 四、设计方案中存在但当前缺少对应后端接口的页面

以下页面可以先做视觉和信息架构，但接入前需要补 Python API/DTO：

| 页面 | 需要补齐的接口域 |
|---|---|
| 教师班级、学生列表、学生详情 | 班级关系、学生列表、学习记录分页、教师可见性 |
| 练习管理与分发 | 练习包 CRUD、分发、提交、批改、同类题生成 |
| 错题本与学习报告 | 错题 CRUD、错因标签、报告聚合、复习计划 |
| Prompt/Skill 管理 | 模板版本、变量 schema、diff、发布、回滚 |
| Agent 配置中心 | 节点开关、模型路由、top_k、引用策略、预算 |
| 模型与供应商管理 | provider/model CRUD、价格、降级规则；密钥只能服务端保存 |
| VIP/额度/费用 | 套餐、额度、用量明细、成本排行、人工调整审计 |
| 黑名单/限流/熔断 | 风控规则、名单、验证码统计、策略发布 |
| 运维队列 | 当前 Python 运行时的任务/同步/索引监控聚合 |

## 五、统一视觉规范

- 气质：学术、可信、克制、专业，不做泛 AI 紫色渐变。
- 色彩：`#0B1736` 深海军蓝导航，`#C99A4A` 暖金强调，`#F6F7F9` 内容底色，`#FFFFFF` 卡片，`#1B2438` 正文，成功绿/告警琥珀/错误红只用于状态。
- 字体：中文使用系统无衬线，数字和英文使用 DM Sans/Inter；数学公式使用 KaTeX。
- 组件：圆角 12px 左右、细边框、轻阴影；关键数据用大留白和对齐表达层级。
- 布局：桌面优先 1440px；侧栏 240px，顶部 64px，内容最大宽度 1440px；平板收缩侧栏，手机改为底部导航。
- 动效：只使用 150–250ms 的淡入、抽屉、状态过渡；任务运行用细进度线，不使用炫目的粒子效果。
- 证据：所有引用统一为 Evidence Card；包含来源范围、标题、章节/页码、snippet、queryId，点击打开原文。
- 数学：公式不被截断；长公式横向滚动；LaTeX/PDF 预览必须和版本绑定。
- 文字：页面默认中文，避免英文占位文案、乱码、假数据、重叠、过密卡片。

## 六、可直接交给 Stitch 的总提示词

```text
为“高中数学教学 Agent 平台”生成一套高端、可信、可落地的桌面端 Web 产品界面，中文界面，React/Vite 风格。

产品不是普通聊天机器人，而是连接学生学习、教师备课、教材 RAG、教师资料、知识图谱、教学任务、多 Agent 编排和安全审计的教学操作系统。整体视觉采用深海军蓝侧栏 #0B1736、暖金 #C99A4A、暖白灰内容区 #F6F7F9、白色卡片；学术感、专业感、克制、高端，不使用大面积紫色渐变、赛博朋克、夸张玻璃拟态或营销落地页风格。

生成完整的登录后应用壳：左侧可折叠导航、顶部工作区/全局搜索/通知/系统健康/用户菜单、响应式内容区。按角色显示“学生学习”“教师教学”“系统治理”分组。页面要有真实产品级空状态、加载骨架、失败状态、权限不足、额度不足和任务运行状态。

请生成以下核心页面及页面间交互：
1. 学生学习首页：掌握度、薄弱知识点、今日任务、待复习和最近 AI 讲解。
2. AI 解题页：题目文本/图片上传、OCR 与公式确认、分步解题、知识点、方法、易错点、追问、教材证据卡、收藏/会了/不会/加入错题。
3. 教师工作台：班级概览、学生风险、资料同步状态、待处理任务、讲义快捷入口。
4. 教材检索页：搜索、章节筛选、结果列表、页码、匹配分、证据摘要、右侧教材页面预览和检索审计。
5. 教师资料库：飞书发现、本地上传、资料表、同步时间线、解析/索引状态、资料块阅读器。
6. 知识库：知识点树、SVG 风格知识图谱、节点详情、关系、题库列表。
7. 教学任务与讲义工作台：任务创建、CREATED/RUNNING/COMPLETED/FAILED 时间线、教师版/学生版/讲解版、LaTeX/PDF 预览、反馈和导出。
8. Agent 广场：Agent 卡片、输入输出契约、权限/资料范围、执行 Plan 确认、阶段 Trace、token/耗时/成本摘要。
9. MCP 与系统设置：工具清单、MCP key 创建与撤销、后端/向量索引健康、capability 审计。

所有页面使用清晰的中文真实文案，不使用 Lorem ipsum，不制造不存在的接口或数据。接口占位请按以下真实资源命名：/api/students/dashboard、/api/students/explanations、/api/retrieval/textbooks/search、/api/teacher/resources、/api/knowledge/points、/api/teaching/tasks、/api/agents/registry、/api/agents/run-plan、/api/mcp/keys。高成本操作在按钮旁显示权限、额度和确认提示。不要展示原始 Chain-of-Thought，只展示可审计的阶段摘要和证据引用。

请优先输出桌面 1440×1024 的高保真页面，保证所有文字、公式、按钮、表格列、侧栏和卡片不重叠；然后补充 1024 和 390 宽度的响应式规则。页面之间保持同一设计系统、间距、颜色和组件样式。
```

## 七、建议按页面拆分给 Stitch 的提示词

### Prompt A：学生学习空间

```text
设计“高中数学 AI 学习空间”桌面端。左侧导航突出学习首页、AI 解题、知识进度、错题本、讲解历史；主区顶部显示“早上好，继续攻克函数与导数”，下方为掌握度总览、今日学习任务、薄弱知识点横向条形图、待复习题目和最近讲解。右侧显示一张安静的学习建议卡。
使用深海军蓝、暖金、白色卡片和浅灰背景。数据必须有标题、单位、更新时间和可点击去向。设计完整的 loading、空状态、错误、低置信公式确认和权限状态。不要做成聊天窗口，不要用假头像堆砌，不要文字重叠。
```

### Prompt B：AI 解题与证据

```text
设计“AI 数学解题工作台”。采用三栏布局：左栏题目输入/拖拽上传图片/识别确认，中栏分步讲解卡，右栏教材与教师资料 Evidence Cards。中栏按“题意理解、知识点、解题方法、逐步推导、易错点、同类练习”分组，数学公式使用 KaTeX 风格并保证长公式不溢出。顶部显示会话标题和处理状态，底部提供追问输入框。
必须体现真实教学可信度：证据卡展示来源、章节、页码、snippet、queryId；显示“查看原文”。提供收藏、会了、不会、加入错题。流式生成时显示阶段进度与可取消状态，不展示原始 CoT，不使用假数据和紫色 AI 光效。
```

### Prompt C：教师工作台与资料库

```text
设计“教师教学工作台 + 资料库”。工作台展示班级掌握度、需要关注的学生、薄弱知识点、最近资料同步、待审阅讲义和快捷操作。资料库用顶部切换“飞书发现 / 本地上传 / 我的资料”，主表展示标题、来源、权限范围、解析状态、向量索引状态、更新时间和操作。详情使用右侧抽屉展示资料 blocks、图片资产和引用。
资料同步用可恢复时间线展示：登记、下载、解析、切块、索引、可检索；失败节点显示错误原因、重试、恢复 checkpoint。高风险删除是归档而非硬删除，按钮必须二次确认。风格克制、专业、信息密度高但不拥挤。
```

### Prompt D：知识库与知识图谱

```text
设计“高中数学知识库”页面。左侧是可搜索的章节/知识点树，中间是可缩放拖拽的 SVG 风格知识图谱，右侧是选中节点详情，下方是题库表格。图谱图例清晰区分章节模块、知识点、解题方法和关系类型；悬停节点时只高亮相关节点和连线。节点详情显示定义、别名、前置知识、关联题型、关联资料和掌握风险。
视觉上使用蓝色知识节点、暖金章节节点、紫色只作为方法节点小面积标识。避免图谱节点与文字重叠；提供“重排布局”“聚焦节点”“放大缩小”“返回中心”。
```

### Prompt E：教学任务与讲义工作台

```text
设计“AI 教学任务与讲义工作台”。左侧为创建任务表单：年级、教材章节、知识点、教学目标、学生画像、课时、模板、输出版本；右侧为任务状态与生成结果。生成中展示 CREATED、RUNNING、COMPLETED、FAILED 状态时间线和阶段事件。完成后提供教师版、学生版、讲解版标签页，分别展示 PDF 页面预览、LaTeX 源码、引用证据和反馈。
顶部提供“继续生成”“恢复任务”“导出 PDF”“导出 LaTeX”“批量 ZIP”。学生版不得泄露答案，教师版显示评分点。PDF 预览要有页码、缩放和版本标识。失败时显示真实错误区、traceId 和重试入口，不要用假进度。
```

### Prompt F：Agent 广场与系统治理

```text
设计“Agent 广场与系统治理后台”。Agent 广场左侧按检索、写作、审校、排版、主控分类，中央是专业 Agent 卡片，右侧是输入输出契约、资料范围、工具范围、预计模型、预算和权限。点击运行后先展示可审计 Plan，再提供确认执行。详情展示阶段 Trace、耗时、token、成本、artifact 和错误恢复。
系统治理页面包括模型健康、向量索引状态、MCP 工具、MCP key 创建/撤销、安全 capability 审计。密钥只在创建成功时一次性显示，列表不回显。不要展示原始思维链，不把管理后台做成密集的黑色监控墙；保持白色内容卡片、深蓝导航、暖金操作重点。
```

## 八、前端落地优先级

1. 先统一 App Shell、登录、权限守卫、API 错误提示、Evidence Card、状态标签、公式渲染和 PDF 预览。
2. 第一批接通真实接口：学生首页、AI 解题、教材检索、教师资料、知识库、教学任务。
3. 第二批完善 Agent 广场、多智能体写作、MCP/系统设置和审计。
4. 后端补齐后再做班级/练习/错题/Prompt/计费/风控管理页；这些页面不要先写死假接口。
