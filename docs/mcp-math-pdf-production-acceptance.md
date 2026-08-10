# 数学 PDF、MCP 与 Terra 生产验收记录

## 发布边界

文件上传是浏览器会话下的 REST 操作；MCP 是已入库资源的受控读取、检索、讲义生成与导出边界。MCP 不接收本机路径或二进制 PDF，也不允许调用方传入 tenant、角色或用户身份。

| 环节 | 入口/工具 | 身份和权限 | 可验证输出 |
|---|---|---|---|
| 上传 | `POST /api/teacher/resources/upload` | 会话主体 + 一次性 capability；服务端重算 multipart 清单哈希 | 隔离的资源 document、同步任务 |
| 资源读取 | `list_teacher_resources`、`read_teacher_resource_blocks` | MCP Bearer key 映射的 tenant/owner + `teacher-resource:read` | 可见资源、原始解析块，不返回存储路径 |
| 检索 | `search_teacher_resource_evidence` | 同一 owner、审计 endpoint/tenant/subject | evidence refs、queryId、检索审计 |
| 讲义 | `start_multi_agent_writing`、状态/成品/trace 工具 | MCP key owner + `agent-writing:*` | 可恢复 workflow、阶段 provider/model、artifact |
| 导出 | `export_multi_agent_writing_artifact` | workflow 所有者 + `agent-writing:export` | Base64 PDF、MCP SHA-256、过期时间 |

## 教师开户与任意上传

公开 `POST /api/auth/register` 仍只创建 student，不能让调用方自行声明 teacher/admin。已登录管理员可在前端“系统设置 → 开通教师账号”提交用户名和初始密码；前端只调用 `POST /api/auth/teachers`，不提交 tenant、role 或 userId。该接口强制继承管理员会话的 tenant，且响应只包含 `userId`、`username`、`role`、`tenantId`，不会返回 password hash 或新教师登录令牌；成功后浏览器清空输入密码且保留当前管理员会话。新教师登录后即可申请上传 capability、上传任意允许解析的 PDF、创建自己的 MCP key。

`selectedFileNames` 不属于线上权限策略，也不参与 `POST /api/teacher/resources/upload`。它仅存在于 `config/*.json` 的离线数学卷目录扫描配置中：在已知包含多学科卷、答案卷的目录里精确选择运营批次，避免批处理错误入库。线上上传按会话 owner、文件大小、路径安全、MIME/解析状态与 capability 清单校验处理，上传文件名不需要出现在任何离线配置。

## 真实通过证据（2026-08-02）

### 数学 PDF 上传、同步与 MCP 检索

真实来源为辽宁省名校联盟 2026 年 5 月数学卷，SHA-256 为 `0a1d79c2167e4c1b5ec38ff10702dc5c63d62a793ea73e5cc10ef4a806cce4e8`。

- 资源 document：`2083831166228156417`，同步状态 `completed`；主体为 `default/admin/admin`。
- 新建 MCP key 的 owner 为 `admin`；初始化协议为 `2025-11-25`，随后 `list_teacher_resources`、`read_teacher_resource_blocks` 和 `search_teacher_resource_evidence` 都真实执行。
- 检索返回 2 条命中，审计 queryId 为 `07de87f0-28f6-49e8-8892-5d3d8aa0a73d`，审计主体 `admin`，endpoint 为 `/api/mcp/tools/search_teacher_resource_evidence/call`，key 已记录 `lastUsedAt`。
- 脱敏原始证据：`output/mcp-acceptance/mcp-math-pdf-20260802T162400Z/acceptance-summary.json`、`output/mcp-acceptance/mcp-math-pdf-20260802T162400Z/mcp-tools.json`、`output/mcp-acceptance/mcp-math-pdf-20260802T162400Z/resource-search.json`。

### 独立教师、非白名单 PDF 与 MCP 隔离

管理员经真实 HTTP 创建两名新的 teacher；开通响应不含 password 字段。教师 A 上传的真实文件为 `2008年高考数学试卷（文）（上海）（空白卷）.pdf`，SHA-256 `9363b0ef6cbe553bf9ead2b2110d82392bd3ad1b1924909c558099d556403be4`。验收脚本实际读取 `config/gaokao-ingestion-2024.json` 和 `config/math-paper-ingestion-liaoning-2026-05.json` 两份离线配置，确认该文件未出现在 `selectedFileNames`，再上传并完成同步。

- document 为 `2083841251283300353`，同步 `completed`；教师 A 的 MCP key 为 `e350e44d-4d2f-47b4-8f96-98d55acb916c`，资源列表为 1、解析块为 1、检索命中为 3。
- key owner、MCP 检索 audit subject 与教师 A userId 均为 `teacher-da7ed356-266e-4e24-b835-3c6467543d03`，queryId 为 `caf6471a-44fa-4102-baf6-ed182435484d`，并有真实 `lastUsedAt`。
- 教师 B 的独立 MCP key 调用同一 document 的 `read_teacher_resource_blocks` 返回“Teacher resource is not visible”；脚本将该拒绝作为必须通过的所有者隔离检查。临时 key 在验收结束后均撤销，因此管理员和其他教师均不能从自己的 key 列表读取它们。
- 证据：`output/mcp-acceptance/independent-teacher-mcp-20260802T170404Z/acceptance-summary.json`、`output/mcp-acceptance/independent-teacher-mcp-20260802T170404Z/owner-resource-search.json`；可复跑脚本为 `scripts/local/run-real-independent-teacher-mcp-acceptance.ps1`。

### 安全修复后的复验（2026-08-02 18:15）

本轮先重建了包含最终权限和上传清单修复的 backend 镜像，再以 Windows 本机 HTTP 对真实高考数学卷复验。上传文件为 `2008年高考数学试卷（文）（全国卷Ⅰ）（空白卷）.pdf`，SHA-256 为 `ca23e36a985003bbf2647a1a06351f1365d61f8888321d1d7aaf2b880db9931a`；它不在两份离线配置的 `selectedFileNames` 中。

- document `2083859136463527938` 已完成同步；教师 owner 的 MCP 资源列表为 1、解析块为 1、检索命中为 3，检索 queryId 为 `66c291f9-a7d1-43e0-8b05-f59e08c7926f`。
- owner MCP key、检索审计 subject 与后端登录教师 userId 完全一致，且 key 的 `lastUsedAt` 已更新；第二名教师以独立 key 读取该私有 document 被明确拒绝。
- 资源图片搜索响应只解析生产写入的 `[{"assetId":"…"}]` 格式，并在最终命中阶段按同一租户/owner/scope 校验后返回安全资产 URI；旧式字符串数组不会被误当作已验证资产。
- 后端同时强制非管理员新资源使用 `TEACHER_PRIVATE`，管理员才可显式发布共享范围；REST 资源块检索也只允许后端认证的 teacher/admin，避免 student 绕过读取边界。
- 脱敏证据：`output/mcp-acceptance/independent-teacher-mcp-20260802T181508Z/acceptance-summary.json`、`output/mcp-acceptance/independent-teacher-mcp-20260802T181508Z/owner-resource-search.json`。

### Terra MCP 教师讲义与 PDF

通过 `/api/mcp` JSON-RPC 启动的 workflow 为 `16ad6709-04d6-45fb-b3da-f2849f7ff742`，所有阶段归属 `default/admin/admin`。输入为两道高三数学题的 `questions` 数组；没有按公式空格或普通空行切分。

- `resource_curation`、`teacher_writer`、`student_writer`、`lecture_writer` 四个阶段均 `COMPLETED`；trace 的 provider/model 均为 `openai/gpt-5.6-terra`。
- 总模型用量：`48,607` prompt tokens、`9,837` completion tokens、`58,444` total tokens；trace 中保留一次 JSON 修复重试，最终阶段都成功，未伪造成功状态。
- 仅经 MCP `export_multi_agent_writing_artifact(format=pdf-teacher)` 导出教师 PDF；MCP 与 Windows 本地字节 SHA-256 一致：`6d8c274d875f303f6613670b8e991a105db1445bf115564d499088ba70c1fce6`，大小 `69,833` bytes。
- Windows 原生 Poppler 已将 PDF 渲染为 3 页 PNG，供逐页人工审阅：`output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/teacher-handout-1.png`、`output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/teacher-handout-2.png`、`output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/teacher-handout-3.png`。
- 脱敏证据：`output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/acceptance-summary.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/artifact.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T164817Z/trace.json`。临时 MCP key 在脚本退出时已撤销，任何证据均不保存 secret。

### Terra 复验（2026-08-02 18:15）

在重建后的 backend 上，以 `questions: string[]` 启动了新的两题 workflow `5ab328e6-fd67-4cbb-b912-6f1c717b9869`。该 workflow 仅由 `/api/mcp` JSON-RPC 创建、轮询、读取和导出；四个阶段均完成且 trace 的 provider/model 均为 `openai/gpt-5.6-terra`。

- 总真实用量为 61,798 prompt tokens、11,408 completion tokens、73,206 total tokens；workflow、artifact 与 trace 主体均为 MCP key owner `admin`。
- `pdf-teacher` 导出文件 SHA-256 为 `7ed7e0f23f90425c3d4d8d12640329df937dbace2671a437c6487dbdb61fc373`，大小 60,644 bytes；Windows 原生 Poppler 已成功渲染 1 页 PNG。
- 脱敏证据：`output/mcp-acceptance/mcp-terra-handout-20260802T181546Z/acceptance-summary.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T181546Z/artifact.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T181546Z/trace.json`。

### 显式分隔符多题复验（2026-08-02 18:50）

重建后的 backend 使用 MCP `questionText` 的独占行 `---` 提交两题，workflow 为 `94ea3900-328a-4c67-b19a-c7c004b18bce`。服务端返回的 `batchInput` 明确记录 `questionCount=2`、`splitMode=question_text_explicit_markers` 与 `whitespaceSplitsQuestions=false`，因此公式空格和普通空行没有参与切题。

- `resource_curation`、`teacher_writer`、`student_writer`、`lecture_writer` 均为 `COMPLETED`，trace 的 provider/model 均为 `openai/gpt-5.6-terra`；总真实用量为 45,200 prompt tokens、7,652 completion tokens、52,852 total tokens。
- Terra 的学生版第一次结构化输出未通过 JSON 校验，系统按既有真实修复/重试策略再次调用；最终工作流只在四阶段全部完成后标记 `COMPLETED`，没有伪造一次调用即成功。为降低同类重试，所有发布写作阶段的提示已明确要求只返回其固定键的单个 JSON 对象。
- 仅由 MCP `pdf-teacher` 导出的教师 PDF SHA-256 为 `8c7d8282c63ae587ec937504383ce7bd2e3b9614cc9fd0178ae86d6da4b327fc`，大小 38,423 bytes；Windows 原生 Poppler 成功渲染 1 页 PNG。
- 脱敏证据：`output/mcp-acceptance/mcp-terra-handout-20260802T185006Z/acceptance-summary.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T185006Z/artifact.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T185006Z/trace.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T185006Z/teacher-handout-1.png`。临时 MCP key 已由脚本撤销，证据未保存 secret。

### 最终图片嵌入复验（2026-08-02 19:17）

为防止授权资源图在 PDF 中以 Markdown URI 文本泄露或丢失，PDF 导出现在仅在 artifact owner 的上下文中调用资源资产服务，将可读 PNG/JPEG 复制到隔离 XeLaTeX 目录后以相对文件名嵌入；不支持、缺失或无权的资产不会触发网络请求，也不会泄露存储路径。独立 `.tex` 导出则将 URI 脱敏为说明文本，因为它不携带受权二进制资产。

- 复用同一已完成的 Terra workflow `a3bd437c-f5b0-4a42-bd8b-18f75b38e49b` 经 MCP owner `admin` 重新导出，未重新调用模型；原始四阶段、provider/model 和 token 审计保持不变。
- 最终教师 PDF 为 13 页、588,555 bytes，SHA-256 `12fbf1f6782d6b1f0c2405cdfe609dd7476588f58121c29bf8811f82de7bfd8e`；Windows Poppler 成功渲染 13 页。人工抽检第 1、2 页确认题干与公式正常排版，已授权的试卷页图实际嵌入且没有 URI 或临时文件名泄露。
- 脱敏证据：`output/mcp-acceptance/mcp-terra-handout-20260802T191753Z/acceptance-summary.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T191753Z/trace.json`、`output/mcp-acceptance/mcp-terra-handout-20260802T191753Z/teacher-handout-01.png`、`output/mcp-acceptance/mcp-terra-handout-20260802T191753Z/teacher-handout-02.png`。

### 发布前 MCP 契约修复与复验（2026-08-02 19:55）

发布审计发现 `tools/list` 曾存在三处会误导严格 MCP 客户端的发现契约问题，均已在运行时修复并做真实 HTTP/MCP 复验：

1. `start_multi_agent_writing` 的后端已支持首选 `questions[]`，但旧 schema 将 `questionText` 单独标记为必填；现改为不对单个字段声明必填，并在工具描述中明确调用方必须提供 `questions`、`questionText` 或 `question` 之一。
2. `search_multi_source_evidence` 和 `search_teacher_resource_evidence` 支持 `library` 或 `libraries`，但旧 schema 误把 `libraries` 标为必填；现只要求 `query`，保留服务端对“至少一个库选择器”的实际校验。
3. 教师私有资源相关工具曾在 discovery 元数据中包含 student 或错误的 `TENANT_PUBLIC` scope 文案；现与执行层一致，仅对 teacher/admin 暴露，并声明实际 scope `teacher-resource:read`。租户、owner 与权限域仍由后端检索服务强制校验，不由 MCP 参数决定。

修复后已先执行 `ProtocolDiscoveryServiceTest` 的红绿验证，再完成发布范围的 Java 测试、`mvn -q -DskipTests package` 和重建后的 backend Docker 镜像。Windows 访问 `GET /api/system/health` 返回 HTTP 200 / `UP`；Docker 中 backend、ai-worker、MySQL、Redis 与 Milvus 均为 healthy。前端 `src/shared/api/textbookApi.test.ts` 与 `src/app/teacherResourceSyncVisibility.test.ts` 共 44 项通过，`npm.cmd run build` 通过。

- 新 admin MCP key `3dfaf41c-8d7e-43d0-87fb-d732d28b56d3` 的真实 `tools/list` 返回 16 个授权工具。讲义工具 `required=[]`，描述明确“空格和普通空行不切题”；两项资源检索工具 `required=[query]`，因而严格客户端可合法使用单一 `library`。该临时 key 已撤销。
- 新独立教师验收：`output/mcp-acceptance/independent-teacher-mcp-20260802T195510Z/acceptance-summary.json`。真实上传的 `2008年高考数学试卷（文）（全国卷Ⅰ）（空白卷）.pdf` 不在两份离线 `selectedFileNames` 中，仍完成 document `2083884309334097922` 同步。MCP key owner、检索 audit subject 与登录教师 userId 同为 `teacher-83d827bf-0792-416e-8d6a-9d2d49078354`，`lastUsedAt` 已更新；另一教师读取私有 document 被拒绝。
- 新 MCP 讲义复验：`output/mcp-acceptance/mcp-terra-handout-20260802T195406Z/acceptance-summary.json`。仅经 MCP 使用新 key 读取并导出已完成 workflow `a3bd437c-f5b0-4a42-bd8b-18f75b38e49b`，不重复消耗模型调用；四个阶段均为 `openai/gpt-5.6-terra` 且 `COMPLETED`。教师 PDF 为 13 页、588,555 bytes，SHA-256 `50fbd4914a6da3ef6758d58d303a12816597a32b34edd7b5548bffe188ca4aa2`，Windows Poppler 已渲染 13 页 PNG。

## 批量题目契约

`start_multi_agent_writing` 的首选输入是有序 `questions: string[]`。若必须传 `questionText`，仅独占一行的 `---`、`###`、`<<<QUESTION>>>`，或行首“第 N 题/题目 N/Question N”可分题；空格与空行绝不分题。系统为每题增加稳定的 `【题目 N】` 标签，再交给原有的同一持久化 workflow，因此同一 owner、trace、恢复与 PDF 导出链路不分叉。

## 自动化与回归

`scripts/local/run-real-mcp-math-pdf-acceptance.ps1` 负责真实 PDF 上传、同步和 MCP 资源验收；`scripts/local/run-real-mcp-terra-handout-acceptance.ps1` 负责只通过 MCP 调用 Terra、核对 artifact/trace/PDF、撤销临时 key，并可用 `-WorkflowId` 重接已持久化 workflow，避免重复模型调用。

本轮完整通过的针对性 Java 组为 `MultiQuestionTextParserTest`、`McpAccessPolicyTest`、`ApiAccessControlServiceTest` 与 `TeacherResourceControllerTest`。前端 `src/shared/api/textbookApi.test.ts` 和 `src/app/teacherResourceSyncVisibility.test.ts` 共 44 项通过，`npm.cmd run build` 通过。Windows 本机 HTTP 复验期间，WSL Docker 内 backend、ai-worker、MySQL、Redis 与 Milvus 均为 healthy；ai-worker `/health` 返回 HTTP 200。backend 使用 Docker healthcheck，未将不存在的 `/actuator/health` 当作应用验收路由。
