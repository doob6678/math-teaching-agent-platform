# 双曲线讲义历史列表与进度展示调查记录（2026-09-07）

只调查、未改任何代码。老板反馈两点：①左侧历史列表看不到正在生成的"双曲线综合学习"讲义；②进度区状态矛盾（生成中 + 校对结论"已通过" + 结构内容 0 项）。以下为数据库、后端日志与代码级证据。

## 一、双曲线讲义确实存在，且正在正常生成

- 任务：`5b39e0c5-8917-48a1-81e1-77fb371df219`，`teaching_task` 表（math-agent-rag-mysql-1 / math_agent_rag 库）。
- 状态：RUNNING，`current_stage=AI_DRAFT`，`retry_count=1`，`last_error=NULL`。
- 时间线（DB 存 UTC，本地 = UTC+8）：14:31:33 创建 → 14:31:34 worker `local-lecture-worker` 拿到租约（租约至 15:11:34）→ 14:36 resource_curation 批准（evidence 12/74）→ 14:39:07 plan_writer APPROVED → 14:39:08 `plan_ready` → 之后快照 `updated_at` 每 1~2 分钟持续前进（14:41、14:42、14:43:34 均确认在更新）。
- 证据 20 条、节点 20 个与前端"来源 20 条"一致；`teacherHandoutLatex` 长度 0（正文尚未产出，Writer 仍在写作）。
- 后端日志确认 AI 工具链正常：`handout_document_inspection` context/read 调用 14:31~14:33 成功返回。
- 结论：**不是僵尸任务**。注意 MySQL datetime 为 UTC，勿把 06:31 误读成早上（本次调查初期曾因此误判 8 小时停滞）。

## 二、左侧历史列表缺双曲线的根因：后端过滤器把 RUNNING 任务挡掉

- `TeachingWorkflowService.isFrontendDisplayableTask`（backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowService.java:989-990）：
  ```java
  if (task.status() != TeachingTaskStatus.COMPLETED && task.status() != TeachingTaskStatus.FAILED) {
      return false;
  }
  ```
  `/api/teaching/tasks` 的 `listRecent`（同文件 :650-659）用它过滤，因此 RUNNING/CREATED 任务**永远不会出现在历史接口返回里**。
- 该代码与自身注释矛盾：:970-971 明确写着 "Failed/running snapshots are intentionally visible"（运行中快照应可见，用于刷新后恢复进度）。
- 前端侧不是阻碍：`HandoutHistorySidebar.isDisplayableHistoryTask`（frontend/src/app/components/HandoutHistorySidebar.tsx:104）本来就允许 `CREATED/RUNNING/FAILED/COMPLETED`，只要后端返回就会显示"生成中"条目。
- 生成完成（COMPLETED 且有可读正文）后，前端在终态回调里 `refreshTeachingHistory()`（App.tsx:1472-1476），届时会正常入列。
- 中途刷新页面时当前任务靠 localStorage 恢复（`math-agent:teaching-task-recovery`，App.tsx:114/1902-1904），所以主工作区能续上，但历史列表仍空——与截图现象一致。
- 附带观察：历史列表上限 `limit=20`（前端 textbookApi.ts:3134 默认 20；后端先取 `limit*3=60` 再过滤截断），过滤后恰好显示 20 条，属正常但接近饱和。

## 三、"校对结论：已通过"是默认值假信号

- 前端 `buildReviewSummary`（HandoutWorkspacePreviewPanel.tsx:453-471）只看 `task.draftReview.status === "READY"` 就渲染"已通过"徽标和"结构化校对已通过 / 当前版本可以继续预览或下载"横幅，不参考任务状态。
- 真正的 READY 来源是后端 VO 构造器默认值：`TeachingTaskResponse`（backend-java/src/main/java/com/doob/mathagent/teaching/vo/TeachingTaskResponse.java:78-83）在 `draftReview == null` 时默认 `new TeachingDraftReview("READY", [], [])`，`mergeResult` 同样默认 READY。
- DB 证据：本次 RUNNING 任务快照里 `draftReview.status=READY`、`findings=0`，但 `teacherHandoutLatex` 为空——校对根本没跑过，READY 是空值兜底。
- 同样的 READY 兜底还在 `TeachingDraftMerger.java:31`。
- 连带项："结构内容 0 项"是因为 AI_DRAFT 阶段 `draftSections` 还是空占位（VO :75-77 默认空数组），本身正常，但与假"已通过"同屏就构成误导信号。

## 四、其他小观察（未定性为缺陷）

- "下载 PDF"按钮 RUNNING 时确实 `disabled`（HandoutWorkspacePreviewPanel.tsx:146），但 primary 绿色样式禁用后仅 `opacity:0.52`（styles.css:4033-4038），视觉上仍像可点，易误判。
- 14:29 后端有两条教材页图 404：`/api/resources/textbooks/math_b_xuanze_bixiu_2/pages/127|65/image` → "Textbook page image not found"。发生在本次任务创建之前，与本任务无关，但选必二第 65/127 页图资产缺失值得后续核对。
- 租约回收链路存在且启用（`LectureTaskLeaseRecovery`，30s 扫描，两栈 `MATH_AGENT_RABBITMQ_LISTENERS_ENABLED=true`）；本次任务租约未过期，不涉及。

## 五、修复建议（待老板下令，未动代码）

1. 后端 `isFrontendDisplayableTask` 放行 CREATED/RUNNING（对齐 :970 注释与前端预期），或至少放行"租约未过期"的 RUNNING，避免历史列表与恢复语义脱节。
2. `draftReview`/`mergeResult` 的 null 默认值不应是 READY：可默认 null 让前端显示"未校对"，或前端在 `status !== COMPLETED` 时不渲染校对结论与"可预览或下载"文案。
3. （可选）禁用态 primary 按钮加灰度样式，避免"看起来能点"。

---

# 第二节：双讲义零图片根因与修复（2026-09-07 下午，老板授权"改正"后实施）

老板复验发现最新双曲线讲义完全没有图片，授权排查改正。结论：**AI 不是没想配图，而是图片链路在 Java 侧四处断链**。以下根因、修复与验证。

## 一、根因链（四处断点，从上游到下游）

1. **broker 不下发来源域信号**：`AgentToolBrokerController` 的 context/search 返回项没有 `transparentRef` 字段。Python `handout_runtime.py:1633-1639` 只按 `gaokao://canonical/{docId}/question/{n}` 前缀自动补排 `canonical_question_read`（真题精读，题图 `![source-image:别名](figures/...)` 行只在精读结果里出现）。信号缺失 → AI 从没见过任何题图行，"没主动调用"是表象。
2. **精读绑定不回写账本**：即使 AI 精读了真题，Java 物化的 markdownLine/logicalPath 不写回 `teaching_task.response_json.evidence[].imageRefs`，导出侧无法反查授权。
3. **渲染器丢图片行**：`TeachingWorkflowCorePolicy.renderWriterMarkdown` 把 AI 产出的 markdown 图片行整行丢弃。
4. **教学导出无题图解析**：`TeachingHandoutPdfExportService` 不像 `MultiAgentWritingArtifactExportService` 那样按账本绑定物化 source-image 行，即使 LaTeX 里有图片行也编译不出图。
5. （验收时追加发现）**别名泄漏成图注**：图片行进入 PDF 后，alt 文本 `source-image:xxx` 被当作可见图注印出，违反"别名是不透明传输标签"的契约。

## 二、修复清单（5 处，均带注释说明契约）

| 文件 | 改动 |
| --- | --- |
| `agent/controller/AgentToolBrokerController.java` | context/search 项下发 `transparentRef`（`gaokao://canonical/{docId}/question/{n}`）；守卫：缺 sourceDocumentId/chunkId 或 CANONICAL 缺题号返回 `""`（防 Python 补排必 404 崩溃）。新增 `persistCanonicalFigureBindings`：canonical 精读物化的 markdownLine+logicalPath 合并回写证据账本 imageRefs（与 `persistTeacherSearchEvidence` 同模式）。 |
| `teaching/service/TeachingWorkflowCorePolicy.java` | `renderWriterMarkdown` 仅保留 Java 签发的 `![source-image:...]` 行，其余 markdown 图片行 fail-closed 丢弃。 |
| `teaching/service/TeachingHandoutPdfExportService.java` | 新增 `resolveSourceImageRows`：导出前按账本 imageRefs 建 markdownLine→绑定表，经 `CanonicalMathPaperAssetService.openVisibleQuestionFigure` 每次重校验授权后重写为绝对路径；无绑定→整行丢弃；绑定命中但资产缺失→保留行回退"图片未找到"。注入 canonicalAssetService（`@Autowired(required=false)`）。 |
| `teaching/service/TeachingHandoutPdfExportPolicyPartB.java` | `renderLatexImageCell`：alt 为 `source-image:` 前缀时不印图注。 |
| 同上 Service.java `drawImageCell`（PDFBox 兜底渲染器） | 同一约束，别名不作可见图注。 |

## 三、验证证据（本次运行真实记录）

- 回归：第一轮改动后 117 测试绿（`AgentToolBrokerControllerTest` 9 含新增 transparentRef 用例、`TeachingHandoutPdfExportServiceTest` 38 等）；图注修复后重跑 `TeachingHandoutPdfExportServiceTest` 38 绿（2026-09-07 17:56）。
- 端到端：验证任务 `9a82350c-3e48-49f7-992c-42df283f58f9` COMPLETED，Python 3 次 canonical-question-read、账本 imageRefs 回写、teacherHandoutLatex 含 `![source-image:69bbedca5e57-image-001](figures/q-021-01.png)`。
- PDF：重建部署后重导出 147923 字节；`pdfimages -list` 第 5 页 310×303 RGB 嵌入图；`pdftotext` 全文 `source-image` 出现 0 次；第 5 页渲染 PNG 目检：双曲线焦点三角形题图正常显示于"第 8 题"下，无别名图注，公式与页脚正常。
- 改动已随第三、四节一并提交合并（2026-09-07 深夜老板下令后）。

---

# 第三节：历史列表 RUNNING 与校对假"已通过"修复（2026-09-07 晚，老板下令"立即修复"）

第一节记录的两个问题按建议 1、2 修复（建议 3 按钮样式未动）。

## 一、改动清单

| 文件 | 改动 |
| --- | --- |
| `teaching/service/TeachingWorkflowService.java` `isFrontendDisplayableTask` | CREATED/RUNNING 快照直接放行（标题有效性与协议泄漏检查仍在前，足以排除脏数据），对齐 :970 既有注释；COMPLETED 仍要求可读正文、FAILED 仍要求持久化检查点。 |
| `teaching/vo/TeachingTaskResponse.java` | `draftReview`/`mergeResult` 的 null 默认从伪造 `"READY"` 改为如实 `"PENDING"`。 |
| `teaching/TeachingDraftMerger.java` | 同上，无校对输入不再伪造 READY。 |
| `frontend/.../HandoutWorkspacePreviewPanel.tsx` | `buildReviewSummary` 对 PENDING 返回 null（不渲染通过横幅/徽标），"校对结论"卡片兜底文案"未返回"改"未校对"。 |
| `TeachingWorkflowHistoryVisibilityTest` | RUNNING 断言从 false 改 true（空正文也可见）、新增 CREATED 用例与 `draftReview/mergeResult == PENDING` 默认值回归。 |

## 二、验证证据（本次运行真实记录）

- 后端定向测试 47 绿（`TeachingWorkflowHistoryVisibilityTest` 3 + `TeachingWorkflowServiceTest` 44，2026-09-07 21:23）；前端 `tsc --noEmit` 通过。
- backend+frontend 镜像重建部署（compose 显示两镜像 Built、backend Healthy、frontend Started）。
- 真机 API 验收：提交椭圆任务 `837adbd2-2116-47dd-ab12-3d1287bb33bf`，RUNNING 期间 `GET /api/teaching/tasks` 历史列表**包含该任务**（status=RUNNING，此前永远不可见）；`GET /api/teaching/tasks/{id}` 返回 `draftReview.status=PENDING`、`mergeResult.status=PENDING`、正文长度 0——不再出现"生成中+已通过"矛盾。
- 部署产物核对：`/assets/index-UmtWw1KZ.js` 含"未校对"与 PENDING 判断，且"结构化校对已通过"仅在真实 READY 时渲染。
- 终态复核（轮询至 COMPLETED，2026-09-07 21:34~21:45）：RUNNING 全程 21 次轮询 `PENDING/PENDING`；COMPLETED 后 `draftReview=NEEDS_ATTENTION`、`mergeResult=MERGED`、findings=1，任务持续在历史列表——PENDING→真实校对的转换端到端成立。

---

# 第四节：绑定被进度保存覆盖 + 修复通道两处失灵（2026-09-07 深夜，老板"自己看有没有真实使用图片"复验时发现）

用新任务 `abb2d23e`（立体几何）与 `837adbd2`（椭圆）复验图片链路，发现两类深层问题并修复。

## 一、账本绑定被编排器进度保存覆盖（椭圆任务 PDF 无图的真根因）

- 现象：椭圆任务 teacherHandoutLatex 有 3 行 source-image、账本别名指纹（`sha256(runId|documentId)[:12]`）与 canonical 行一一对应，但账本 `imageRefs=[]`、导出 fail-closed 丢图。
- 根因：broker 精读把绑定写回 teaching_task 后，编排器的进度快照由**运行开始时构建的内存 evidence**（不含绑定）反复 `saveOwnedRunning` 覆盖账本。双曲线任务因 retry 从 DB 重载 checkpoint 才侥幸存活——绑定存活与否纯看时序，系统性脆弱。
- 修复：`MyBatisTeachingTaskStore` 全部更新写路径（save 更新分支/saveOwnedRunning/completeOwned/failOwned/prepareForResume）写库前从已持久化快照按证据身份（scope+docId+chunkId+题号）结转 imageRefs 绑定。回归：`MyBatisTeachingTaskStoreTest` 3 绿（含新结转用例）。

## 二、LaTeX 修复通道两处失灵（abb2d23e 导出落 recovery-stub 的根因）

- AI Writer 产出的行内数学定界符破损（`$BD=O$$，` 悬空 `$`）本属修复闭环设计场景，但闭环从未真正工作过：
  1. compose 注入 `MATH_AGENT_LATEX_REPAIR_PROVIDERS=""`（空串），Python `os.getenv(name, fallback)` 只在**变量缺失**时用 fallback → provider 列表为空 → 503。修复：空串按未配置处理回退讲义 provider 顺序（`latex_repair_runtime.py`，回归测试 6 绿）。
  2. 修复输出是整份文档，16000 tokens 上限被 gpt-5.6-terra 截断 → `REPAIR_TRUNCATED/MISSING_ENVELOPE` 被结构校验拒收。修复：上限 32000、超时 60s→180s（compose 默认值），prompt 合同补"必须逐字输出到 \end{document}"；`PythonTeachingHandoutClient` 补非 REPAIRED 状态的 WARN 诊断（此前拒绝原因不可见）。

## 三、端到端验证（真实记录）

- 新任务 `abb2d23e-2098-4d45-9790-2051ae6ec382`（立体几何）：RUNNING 期间账本 4 行绑定存活（结转生效）；导出日志证明 XeLaTeX 真实加载 `/app/data/math-paper-corpus/.../figures/q-010-01.png`；修复轮 1 次成功（`model repair compiled after 1 round`），终版 PDF 118625 字节 / 6 页，`pdfimages` 第 2 页嵌入 421×270 RGB 题图，`pdftotext` 别名 0 泄漏；第 2 页 PNG 目检：α/β 相交平面与 m/n/s/t 直线题图正常显示，公式清晰，页眉页脚正确。
- 全量回归：teaching 包 790 测试 0 失败；ai-worker 相关 49+6 测试通过。
