# 讲义工作流 Token 与耗时优化（2026-07-28）

## 调整

讲义工作流从 10 个模型阶段压缩为 4 次模型调用：

1. `resource_curation`：一次检索并压缩已授权的飞书正文、来源标注与图片 URI。
2. `teacher_writer`、`student_writer`、`lecture_writer`：在资料整理完成后并行执行。

已移除会重复消费模型上下文的 `template_selection`、`outline_planning`、`source_review`、`student_safety_review`、`layout_review` 与 `merge_coordinator`。

## 保留的发布门禁

- 教师版必须带可读的“资料依据：标题”来源标注。
- 学生版与 16:10 版仍由导出层拒绝答案、教师备注和诊断泄漏。
- 16:10 导出层强制单题投影结构；PDF 审计脚本继续检查单页、16:10 比例、题目首屏、文本预算与占位符。
- 资源检索和图片 URI 仍仅由后端按当前租户/主体授权后注入。

## 预期收益

模型调用数由 10 降至 4；移除的 6 次调用不再传递重复的前序全文。三个版本仍并行，端到端耗时由最慢 writer 加一次资料整理决定，而非串行审校链。

## 验证

`MultiAgentWritingServiceTest#runsOnlyEvidenceAndThreeParallelPublishableVariants` 已先以 4 阶段预期失败，再在实现后通过。

## 真实失败基线（2026-07-28，已落盘）

本节只记录已经发生的真实执行，不把它标为验收通过。原始逐事件 trace 在
`output/acceptance/2026-07-28-live-trace/luna-mcp-react.live.jsonl`，最终快照在
`output/acceptance/2026-07-28-live-trace/luna-mcp-react.json`。

| 指标 | 真实失败值 | 观察 |
| --- | ---: | --- |
| 起止时间（UTC） | 04:46:21 - 04:54:22 | 481 秒（约 8 分钟） |
| Luna ReAct 轮数 | 8 | 不是模型首请求超时 |
| Prompt tokens | 99,445 | 主要浪费项 |
| Completion tokens | 828 | 不是超长回答造成 |
| Total tokens | 100,273 | 必须作为后续对照基线 |
| 首次 Luna 请求 | 4,689 ms | 中转站连通正常 |
| 检索 MCP 调用 | 5,242 ms | 成功返回真实飞书命中 |
| 工作流最终状态 | `COMPLETED` | 写作调用本身并未失败 |
| 导出前失败 | 图片命中门禁 | 检索到的是合法文本块，但该相关块没有图片资产 |

### 已确认根因

1. ReAct 将完整检索命中、工作流状态和 artifact 反复回填给 Luna；第 8 轮可见上下文达到 23,108 prompt tokens，累计达到 99,445。该数据已经保留在 JSONL，不是估算值。
2. RabbitMQ 的消费者此前固定单并发，而三个 writer 虽在工作流图中并列，却被逐个消费。
3. 开启三个消费者后暴露出第二个事实：三个任务同时从同一已完成前缀读取，再以各自的旧快照保存，后写入者会覆盖已完成的同级 stage。因此 trace 长时间显示 `RUNNING`、`stageCount=2`，即使三个 worker 都打印了完成日志。

## 修复与后续真实对照（待本次新运行填写）

- ReAct 仅回传下一步决策所需的 MCP 摘要（状态、workflowId、evidenceRefs）；完整证据和 artifact 只落盘，不再重复进入模型上下文。
- RabbitMQ 消费者并发严格读取 `math-agent.agent-worker.runtime.max-concurrency`，Compose 默认与三个 writer 的上限一致。
- 每个 writer 的模型调用继续并行；仅“重新读取最新 stage + 合并 + 保存”这一极短临界区按 `workflowId` 串行，防止丢失同级结果。
- 相关文本块不带图片不再阻断 PDF 导出；该事实单独记录为 `sharedRootImageHitPresent=false`，而 PDF 审计继续检查实际渲染的图片、公式、字体与版式。

新运行会在相同目录结构下写出：token/时间对照、HTTP/MCP 事件、阶段耗时、三份 PDF、Windows 渲染截图、提取文本与布局审计。只有全部门禁通过后，才在本节补写“修复后真实值”。

### 修复后第一次真实对照（2026-07-28，PDF 编译前已完成）

证据目录：`output/acceptance/2026-07-28-corrected/`。该次工作流四阶段已经真实 `COMPLETED`，但**不能验收通过**：教师 PDF 在 XeLaTeX 编译处失败，尚无三份可交付 PDF。

| 指标 | 失败基线 | 修复后真实值 | 变化 |
| --- | ---: | ---: | ---: |
| 端到端至 PDF 导出 | 481 秒 | 231 秒 | -52.0% |
| Prompt tokens | 99,445 | 9,967 | -90.0% |
| Completion tokens | 828 | 572 | -30.9% |
| Total tokens | 100,273 | 10,539 | -89.5% |
| 写作阶段 | 被并发快照覆盖 | 4 个 stage 完整完成 | 已修复 |

本次错误已另行落盘：XeLaTeX 收到 `\\maketitle\\n\\subsection*{教学目标}`，其中 `\\n` 是字面量而不是真实换行，报 `Undefined control sequence`。这是导出模板的字符串拼接问题，与 Luna、中转站、飞书检索和 worker 阶段无关；下一次运行将只在修复该模板后重新导出并进行三 PDF 视觉审计。

### 最终 PDF 导出（同一真实已完成 workflow）

最终交付目录为 `output/acceptance/2026-07-28-final-pdf/pdf-final-clean/`。三份文件均由 Docker 内 XeLaTeX 生成、通过 MCP Base64 导出与 SHA-256 校验，并在 Windows 以 Poppler 渲染后通过布局审计：教师版 5 页、学生版 5 页、16:10 单题版 1 页。最终 SHA-256 分别为：

- 教师版：`c51394ab12ec6365227e0fc2b60cb1ce0c3b3d06ecc397a32a1715190b87afd4`
- 学生版：`193b3d4591cef3b19bb5ebe27dc2be25d588077619adf225ece76f52e04dfda4`
- 16:10 单题版：`7ba2960239143b0c9e536dcc9cf81ae1db6465cdc0746de7084047e36e91c45d`

视觉复核确认：中文字体一致、标题和页眉页脚使用深蓝/青绿色、公式为 XeLaTeX 排版而非原始标记；学生自由作答区使用留白，表格/单值填空保留横线。所有模型、MCP、轮询和 PDF 异常事件见同目录 JSONL/JSON 记录。

### HTTP 响应码与策略验证

`output/acceptance/2026-07-28-final-pdf/http-observability-complete.jsonl` 记录了真实传输边界：登录、MCP key、initialize、检索、工作流状态和三个 PDF 导出均返回 HTTP 200；共享资料检索为 2,210 ms，三个 PDF MCP 导出分别为 1,695 ms、1,488 ms、1,410 ms。Luna 最小调用也返回 HTTP 200、4,759 ms、41 tokens。

验收脚本现将每一条成功响应即时记录为 `http.response`（method、URL、statusCode、latencyMs）；HTTP 非 2xx、DNS/网络失败和客户端超时分别记录 `http_error`、`network_or_dns`、`client_timeout`，并保留安全响应摘要。凭据、请求正文和 PDF Base64 均不进入 trace。

### 最终视觉修订版

最终可交付文件以 `output/acceptance/2026-07-28-final-pdf/pdf-final-verified/` 为准。该版修复了章节标题中数学分隔符被显示为原始文本的问题；Windows PNG 复核确认标题中的角 `A` 已为数学字体、学生自由作答位置为实际留白、仅填空项保留横线。最终 SHA-256：教师 `2c7f2ecf1ecb5e2629b765a833ba38860a6c769d123fc06c68865243fd7347bf`；学生 `d36c86b9537a6bcd5bb6f95f8896ae279855f54423b13ac0b89b1f861dc05eb6`；16:10 `2c51f1136263af50340d4e8fcc395d8aa8cfc083521c81848e7495e6befb3f6e`。

### 公式结构门禁补充（2026-07-28）

PDF 不是把公式截图后嵌入：Luna 的 Markdown 先由 `FormulaMarkupSanitizer` 归一化为 `$...$` 内的
`\\frac{分子}{分母}`、`\\sqrt{被开方数}`，再由 Docker 内 XeLaTeX/Noto CJK 编译；Windows Poppler
只做真实渲染检查。因此“根号像对号而横线不覆盖被开方数”属于上游数学标记/定界符损坏，不能用字体
替换掩盖。导出层现拒绝 Unicode `√`、裸 `\\sqrt`、裸 `\\frac`、裸数学斜杠、未闭合或混用 `$`/`$$`
定界符，并返回行号和片段；不再生成结构错误但看似成功的 PDF。列表中的 Markdown 图片 URI 和 HTML
留白标签则在转换时消除，避免它们污染正文或被误判为数学斜杠。
