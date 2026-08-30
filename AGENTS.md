# 项目开发指令

开始任何代码、测试或文档变更前，先阅读本文件、[README.md](README.md) 的“不可违反的讲义架构”以及[讲义架构验收清单](docs/handout-architecture-acceptance-checklist.md)。这些规则优先于局部实现习惯。

## 讲义内容边界

- AI/Python Writer 是教师版、学生版、课堂讲解版一切可见教学正文的唯一作者，并决定图片的选用和位置。
- Java 与前端仅承担通用鉴权、持久化、格式化/渲染、可见性隔离和输入/输出校验；不得补写或改写教学语义。
- 禁止在 Java/前端写固定教学文字、主题或知识点分支、默认标题、答案、提示，或自行选择、回退生成示意图和图片。
- 学生版必须与答案、教师批注、推理轨迹及内部来源标识隔离。

## 资产与授权边界

- 图片领域和模型契约只使用不透明 `evidenceRef` 和 `assetId`；不得传递文件系统路径、URL、Base64 或 AI 生成的 LaTeX 图片命令。
- ！！！在讲义写作部分，绝对不可以把图片二进制或者base64传给AI，绝对不可以！！！
- 只有通过当前主体和当前运行授权校验、且已完成权威图片选择校验的资产才能物化给渲染或视觉模型。通用渲染器仅可在此后生成内部 LaTeX 图片标记，不得自行选图或回退；无有效选择时不显示图片。
- RAG 后的 AI 仅能读取或检索本次运行已授权的文档，不得获得任意文件系统或 shell 能力。

## 检索链路架构（强制执行）

**禁止 Java 或前端直接将用户输入作为检索参数**。正确的检索流程为：

1. **Java 负责**：接收用户教学目标、校验权限、创建持久化任务、签发 `runId`、提供初始 `evidenceRefs`（如有）。
2. **Python AI 负责**：
   - 在 `resource_curation` 节点接收 Java 签发的初始证据（可为空）
   - 在 `plan_writer` 节点根据教学目标自主生成检索关键词
   - 通过 `teacher_resource_curation` 节点调用 `handout-teacher-resource-search` 工具，传入 AI 生成的 query
   - 通过 `handout-document-read`/`handout-document-search` 工具深入阅读已授权文档
3. **Java MCP 工具边界**：
   - 校验请求来自已签发的 `runId` 和 worker 密钥
   - 对 AI 生成的 query 执行向量检索（Milvus）
   - 返回不透明 `evidenceRef`、`documentRef` 和受限文本摘要
   - 不暴露文件路径、collection 名称、数据库连接或云存储 URL

**所有资源必须先入库向量数据库**：教材、教师资料、高考题库等必须先通过批量入库流程写入 Milvus，禁止在运行时直接读取本机文件系统或远程存储路径。

**示例（正确）**：
- 用户提交："为高一学生讲解二次函数顶点式"
- Java 创建任务，签发 `runId=task_abc123`
- Python AI `plan_writer` 生成检索 query：`["二次函数顶点式", "配方法", "函数图像平移"]`
- Python 调用 `handout-teacher-resource-search(runId=task_abc123, query="二次函数顶点式")`
- Java 校验 `task_abc123` 后执行 Milvus 检索，返回 `evidenceRef=ev_a1b2c3...`
- 禁止返回给AIeva1b2c3等作为图片链接发送给AI，必须返回真实路径，但是不是绝对路径，确保AI可以阅读并且给出来

**示例**：

- ✅️ Python 读取本机 `/data/teacher-resources/` 目录
- ✅️ 在运行时扫描文件系统搜索资料

## 高中来源入库边界

- 固定顺序为：GPU PaddleOCR/版面分析产生页图和题图资产，Terra/Luna 对同源页面作视觉转写，以完整来源文件名创建资料目录并发布全文 Markdown、逐题 Markdown 与资产，最后执行授权分块和向量入库。
- OCR 与版面结果只用于定位和资产绑定，不能冒充权威题干转写；来源证据、完整来源名和资产哈希必须可复核。

## 交付门禁

- 每次交付均按[讲义架构验收清单](docs/handout-architecture-acceptance-checklist.md)记录本次运行的真实证据。
- 必须验证来源证据/完整来源名、AI 独占可见内容、学生答案隔离、真实来源图片、中文与公式、PDF 视觉审阅。历史报告、旧 PDF 或旧截图不能作为本次验收通过依据。

## 容器与模型缓存

- 经验：Torch/CUDA 重复下载通常是浮动基础镜像 digest 变化使 pip 依赖层失效，并非模型下载；禁止删除 Docker/build/pip/model 缓存或执行 prune。
- 模型只读复用现有 `/models` 权重；worker 应复用固定的 GPU 依赖基底或等价的持久化 pip 缓存，换机器或修改依赖时先重新准备并校验基底，源码重建只更新源码层，禁止运行时下载新模型或重复安装已有依赖。
