# 后端拆分与运行手册

本文是当前仓库的唯一运行和验收入口，描述后端职责边界、配置来源、资源格式、真实数据位置以及一次完整的
教材检索到讲义 PDF 导出流程。文中端口和数量是 2026-07-31 在本机 WSL Docker 实例读取的快照；检索日志会
继续增长，数量不是固定配额。

## 1. 配置来源

配置只有两条启动路径，二者使用同一份变量名：

1. WSL Docker：仓库根目录 `.env` 由 Docker Compose 自动读取，用于插值；`backend` 和 `ai-worker` 又把 AI
   变量显式传入容器。容器不会因为 Compose 读取过 `.env` 就自动看到全部变量。
2. Windows PowerShell：`scripts/local/start-backend.ps1` 先读取进程变量，再读取用户变量，最后以 UTF-8 读取根目录
   `.env`。因此已存在的进程/用户变量优先；没有时才从 `.env` 补齐。脚本同时设置 MySQL、Redis、RabbitMQ、
   Milvus、Worker 和中文字体路径。

本地 AI provider 的确定值为：

```text
OPENAI_BASE_URL=https://api1.aisz.mom/v1
OPENAI_CHAT_MODEL=gpt-5.6-luna
OPENAI_API_KEY=<仅存在于 .env/进程环境，不写入代码和验收文件>
```

`application.yml` 的 Spring AI 和 `math-agent.ai.openai`、Java provider 默认值、Python worker 默认值以及
Compose 缺省值均指向上面的 aisz endpoint；显式设置 `OPENAI_BASE_URL` 时才覆盖它。不能把 relay key 与
`api.openai.com` 混用。

## 2. WSL Docker 服务

| 服务 | 容器端口 | Windows 端口 | 健康检查 |
| --- | ---: | ---: | --- |
| frontend | 80 | 5173 | `GET /healthz` |
| backend | 8080 | 8080 | `GET /api/system/health` |
| ai-worker | 8091 | 8092 | `GET /health` |
| MySQL | 3306 | 3307 | `mysqladmin ping` |
| Redis | 6379 | 6380 | `redis-cli ping` |
| RabbitMQ | 5672 / 15672 | 5674 / 15674 | `rabbitmq-diagnostics -q ping` |
| Compose Milvus | 19530 / 9091 | 19531 / 9092 | `/healthz` |

同一 WSL 里另有一个历史容器 `milvus-standalone` 使用 19530；它不是本项目数据源。项目后端、脚本和验收
统一使用 `math-agent-rag-milvus-1` 的 19531，不能交叉查询。

启动和校验：

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/c/Users/doob/Desktop/code/dev/math_agent_rag && docker compose --env-file .env config -q && docker compose --env-file .env up -d --build"
curl.exe --fail http://127.0.0.1:5173/healthz
curl.exe --fail http://127.0.0.1:8080/api/system/health
curl.exe --fail http://127.0.0.1:8092/health
```

停止服务使用 `docker compose down`；不要使用 `down -v`，除非明确要删除 MySQL、Redis、RabbitMQ、Milvus、
Worker 和 backend 的持久化卷。

## 3. 后端拆分后的职责

原来的大类只保留编排和依赖注入，策略、解析和渲染分别归属小类；生产 Java 文件均小于 2000 行。

| 责任 | 类别 |
| --- | --- |
| 教师资料块检索、过滤、分页、权限和审计 | `TeacherResourceBlockSearchService` + `TeacherResourceBlockSearchPolicy` |
| 本地/飞书资料同步、checkpoint、资产写入 | `TeacherSourceSyncExecutionService` + `TeacherSourceSyncPolicy` + `TeacherSourceSyncParsingPolicy` |
| 讲义任务状态、阶段编排、恢复和证据汇总 | `TeachingWorkflowService` + `TeachingWorkflowCorePolicy` + `TeachingWorkflowExecutionSupport` + `TeachingWorkflowEvidencePolicy` |
| AI 草稿、题目和学生版文本 | `TeachingWorkflowDraftRenderer` + `TeachingWorkflowQuestionRenderer` + `TeachingWorkflowStudentRenderer` |
| LaTeX 版本生成和 PDF 导出 | `TeachingWorkflowLatexRenderer` + `TeachingHandoutPdfExportService` + `TeachingHandoutPdfExportPolicyPartA/B` |
| 阶段进度模型 | `TeachingWorkflowProgressModel` |

每个公共方法的注释说明输入、输出、持久化边界和与相邻策略的联动；策略类不直接拥有 HTTP 生命周期，避免
再次形成 God class。

## 4. 资源类型、格式和落点

| 资源 | 输入格式 | 解析结果 | MySQL | Milvus/文件 |
| --- | --- | --- | --- | --- |
| 公开教材 | 预处理 PDF 页、Markdown、`catalog.json/jsonl` | 页/章节文本、公式、页图 | `source_document`、`document_block` | `textbook_text_collection`、`textbook_image_collection`、`/app/data/textbooks` |
| 教师资料 | `.md`、`.txt`、`.docx`、`.pdf`；飞书导出 `md/docx/pdf` | 文档块、页图、公式和权限元数据 | `source_document`、`document_block`、`teacher_resource_asset` | `math_agent_teacher_text_blocks_bge`、`math_agent_teacher_page_assets_clip` |
| 题库 | 教师资料块或 Markdown/TXT/PDF/DOCX 中可识别编号题 | 题干、答案 JSON、难度、来源块 | `question_bank_item`、`question_source_occurrence`、`question_knowledge_link` | 通过教师文本 collection 检索来源证据 |
| 知识图谱 | `knowledge-spine.md` | 知识点、先修/方法关系 | `knowledge_point`、`knowledge_relation` | 后端图谱接口返回 JSON/SVG 由前端展示 |
| 高考真题 | 真实 PDF 页图 + Luna 识别的 LaTeX | 题干、公式、题号、跨页标记 | `document_block`/题库关联 | `gaokao_math`（512 维） |
| 学生学习数据 | JSON 请求 | 学习快照、反馈、记忆 | `student_learning_snapshot` 等 | 不直接写入教师 collection |
| 讲义 | 结构化任务 JSON、LaTeX、PDF | teacher/student/lecture 三版本 | `teaching_task.response_json`、trace | `output/acceptance` 和容器 `/app/data` |

### 输入示例

Markdown 题目必须使用 UTF-8，图片使用相对路径，公式使用明确的 LaTeX：

```markdown
# 正弦定理

在三角形 $ABC$ 中，已知 $a=5,b=7,A=30^\circ$，判断解的个数。
\[
\sin B=\frac{b\sin A}{a}
\]
```

教师资料注册时 `feishuExportFormat` 只能是 `md`、`docx` 或 `pdf`；`parseMode` 是 `TEXT`、
`MARKDOWN_ASSETS` 或 `AI`。PDF/DOCX 页图作为 `image/png` 资产保存，原始对象不以内部 storage key 直接暴露给学生。

## 5. MySQL 当前快照

以下为 `SELECT COUNT(*)` 的真实结果（2026-07-31，库 `math_agent_rag`）：

| 表 | 行数 | 内容 |
| --- | ---: | --- |
| `agent_run_trace` | 125 | Agent、provider、model、状态、证据引用和成本 |
| `agent_worker_node` | 1 | Java Agent Worker 注册和心跳 |
| `agent_worker_task` | 134 | RabbitMQ 阶段任务租约和重试 |
| `auth_account` | 5 | 登录账户摘要 |
| `document_block` | 343 | 教材/教师资料/高考页级文本块 |
| `feishu_resource_binding` | 2 | 飞书资源绑定 |
| `feishu_user_credential` | 1 | 加密后的 OAuth 凭据 |
| `import_run` | 1 | 导入运行状态 |
| `import_source_file` | 6 | 导入文件清单 |
| `knowledge_point` | 199 | 知识点 |
| `knowledge_relation` | 140 | 先修、方法等关系 |
| `lecture_task_outbox_event` | 8 | 讲义任务事件 |
| `mcp_client_key` | 48 | MCP 客户端密钥摘要 |
| `multi_agent_writing_workflow` | 31 | 多 Agent 写作 workflow |
| `question_bank_item` | 73 | 题干、难度、答案和权限 |
| `question_knowledge_link` | 73 | 题目到知识点的绑定 |
| `question_source_occurrence` | 126 | 同题在来源中的出现位置 |
| `retrieval_hit_log` | 238 | 检索命中明细 |
| `retrieval_query_log` | 43 | 查询、策略、命中数和耗时 |
| `source_document` | 4 | 来源文档和解析/向量状态 |
| `source_sync_checkpoint` | 14 | 增量同步 checkpoint |
| `source_sync_job` | 15 | 资料同步任务 |
| `student_explanation_message` / `student_explanation_session` | 1 / 1 | 学生讲题会话 |
| `student_learning_snapshot` | 2 | 学习快照 |
| `teacher_resource_asset` | 87 | PDF/DOCX/Markdown 图片资产 |
| `teacher_resource_search_audit_hit` | 891 | 教师资料检索命中审计 |
| `teacher_resource_search_audit_log` | 164 | 教师资料查询审计 |
| `teaching_task` | 3 | 教学任务及完整 response JSON |

空表（如 `ai_usage_event`、`canonical_question`、`security_audit_log`、`teaching_human_feedback`）保留为
schema，不代表链路缺失；当前验收没有产生对应业务动作。

关键字段示例：

```text
document_block: id, source_document_id, block_type, page_no, raw_text, normalized_text, image_refs, formula_refs, status
question_bank_item: question_id, question_title, question_text, answer_json, difficulty, source_document_id, status
teacher_resource_asset: asset_id, document_id, page_no, mime_type, storage_key, checksum, status
teaching_task: task_id, status, current_stage, retry_count, response_json, started_at, finished_at
```

验收任务 `94234346-e6a8-488f-8a6f-bca9ee1514ad` 在 `teaching_task` 中为 `COMPLETED`，阶段为
`LATEX_HANDOUT`；响应 JSON 中记录教材 3 条、题库 2 条、教师资料 1 条证据。

## 6. Milvus 当前快照

Milvus 连接参数为 `127.0.0.1:19531`、token `root:<项目 .env 中的密码>`。向量字段不在示例中展开，只记录维度；
标量字段使用 `id`、`text`、`metadata`。Milvus 类型编号为 `21=VarChar`、`23=JSON`、`101=FloatVector`。

| collection | 行数 | 字段和维度 | 示例 |
| --- | ---: | --- | --- |
| `math_agent_teacher_text_blocks_bge` | 343 | `id(VarChar)`, `vector(FloatVector,512)`, `text(VarChar)`, `metadata(JSON)` | `text=SSA求三角形解的个数问题`；metadata 含 `documentId/blockId/sourcePath/blockRole/checksum` |
| `math_agent_teacher_page_assets_clip` | 15 | `id(VarChar)`, `vector(FloatVector,768)`, `text(VarChar)`, `metadata(JSON)` | `text=概率统计/条件概率、全概率与贝叶斯公式.md`；metadata 含 `assetId/pageNo/mime` |
| `textbook_text_collection` | 1070 | `id(VarChar)`, `vector(FloatVector,512)`, `text(VarChar)`, `metadata(JSON)` | `id=math_b_bixiu_4_p008_ai_001`；metadata 含 `chapterPath/pageNo/sourcePageImage/corpusVersion` |
| `textbook_image_collection` | 1121 | `id(VarChar)`, `vector(FloatVector,768)`, `text(VarChar)`, `metadata(JSON)` | `id=math_b_bixiu_4:p0001:pages/p001.png`；metadata 含 `bookName/pageNo/sourcePageImage` |
| `gaokao_math` | 532 | `id(VarChar)`, `vector(FloatVector,512)`, `text(VarChar)`, `metadata(JSON)` | 2024 新课标Ⅱ卷第 13 题；metadata 含 `sourceFile/page/questionNumber/latex/confidence/extraction` |

完整字段和一条标量样例保存在 [milvus-inspection.json](../output/acceptance/2026-07-31-backend-split/milvus-inspection.json)。
该文件不包含 API key，也不展开向量数组。

## 7. 一次完整流程

1. `docker compose config -q` 解析 `.env`；backend/worker 获得相同的 aisz endpoint 和 `gpt-5.6-luna`。
2. MySQL、Redis、RabbitMQ、Milvus 和 worker 健康后，backend 才启动；SimHei 挂载到 `/app/fonts/cjk.ttf`，
   PDF 导出使用 `MATH_AGENT_PDF_FONT_PATH`，避免中文字体回退失败。
3. 教材检索：
   `GET /api/retrieval/textbooks/search?query=正弦定理&limit=10`，真实验收耗时 466 ms，返回 9 条混合 RAG 命中。
4. 教师资料检索：先做权限过滤，再做文本块/页资产两阶段检索；`retrieval_query_log` 和
   `teacher_resource_search_audit_*` 保存 queryId、策略、命中数和耗时。
5. 提交教学任务：

   ```json
   {
     "clientRequestId": "<uuid>",
     "questionText": "在三角形 ABC 中，a=5，b=7，A=30°，判断解的个数。",
     "learningGoal": "SSA 求三角形解的个数",
     "evidenceLimit": 10
   }
   ```

   `POST /api/teaching/tasks` 先返回 taskId/状态，后台按 `LEARNING_GOAL -> REUSE_RESOURCE -> PUBLIC_TEXTBOOK_RETRIEVAL
   -> QUESTION_BANK_RETRIEVAL -> TEACHER_RESOURCE_RETRIEVAL -> REACT_SOLVE -> AI_DRAFT -> LATEX_HANDOUT` 执行。
6. AI 讲题使用 aisz provider 的真实 HTTP 调用；验收模型为 `gpt-5.6-luna`，总 token 5122（prompt 2161、
   completion 2961），没有模拟结果或本地假数据。
7. 讲义渲染生成 `teacherHandoutLatex`、`studentHandoutLatex` 和 `lectureHandoutLatex`。学生版保留作答留白，
   16:10 版只保留单题课堂引导，不泄露教师答案和内部 trace。
8. `GET /api/teaching/tasks/{taskId}/handout/teacher/pdf` 导出 PDF。验收第一次失败是缺少中文字体路径；
   挂载 SimHei 并设置 `/app/fonts/cjk.ttf` 后真实导出成功：3 页、41263 bytes。
9. 浏览器从 `http://127.0.0.1:5173` 完成检索、任务提交、状态轮询、预览和 PDF 下载；后端日志和数据库记录
   用 taskId/traceId 对齐。

## 8. 验收产物和统计

目录 `output/acceptance/2026-07-31-backend-split/`：

- `94234346-e6a8-488f-8a6f-bca9ee1514ad-teacher.pdf`：可打开的 3 页教师版 PDF。
- `task-result.json`：从 MySQL `teaching_task.response_json` 原样导出的有效 JSON，包含节点、证据、三种 LaTeX、
  AI usage 和恢复事件；`ConvertFrom-Json` 已验证 `status=COMPLETED`。
- `task-meta.tsv`：taskId、状态、阶段和时间戳。
- `agent-trace.tsv`：provider/model、阶段耗时、token 统计和诊断事件。
- `milvus-inspection.json`：5 个 collection 的字段、行数和标量样例。

禁止把 API key、Milvus password、Capability token 写入日志、JSON、截图或讲义；文档只写变量名和脱敏占位符。
