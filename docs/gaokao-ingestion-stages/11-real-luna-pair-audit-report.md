# 阶段 11：2024 真文件 Luna 配对实验报告

## 范围与设计

本次只处理配置文件 `config/gaokao-ingestion-2024.json` 中的六份 2024 PDF：北京、新课标Ⅰ和新课标Ⅱ各一份空白卷与解析卷。目录内的上海、天津、全国甲卷均未进入本实验；目录中没有名称为“全国一卷/全国二卷”的文件，因此没有替换为全国甲卷。

产品域仍允许调用方显式指定其它模型；`gpt-5.6-luna` 只固定在本次证据脚本中，以防实验记录混入 provider 回退。模型密钥没有写进配置或证据文件。

## 真实执行与发现

- 真实选中文件：6 份。
- 真实字节总数：2,012,711。
- WSL 首次直连在 DNS 解析阶段卡住，已终止并作为失败证据保留；这不是模型响应，不能计入模型成功或失败。
- 后端 Docker 容器使用 Compose 明确配置的 DNS 进行实际请求。首次复制到 `/tmp` 的脚本因项目相对路径假设退出；已修复为 prepared-request 模式可移植后重试。
- 成功请求模型：`gpt-5.6-luna`；HTTP 200；耗时 12,190 ms；prompt 608 token、completion 495 token、总计 1,103 token（其中 reasoning 153）。

## 结论与限制

模型根据六个真实文件名正确给出三对空白卷/解析卷的 `SAME_QUESTION` 候选，并明确声明没有读取页面内容。该结果只可缩小后续页面配对候选，不能证明逐题题干、答案或图像一致，不能触发自动合并、答案发布或 Milvus 索引。

完整、无密钥的请求形状、prompt、HTTP 状态、usage 和响应保存于：

`output/gaokao-evidence/2024/luna-2024-pair-audit.json`

准备阶段的实际 SHA-256、字节数和选择范围保存于：

`output/gaokao-evidence/2024/luna-2024-pair-request.json`

## 真实视觉页审查

使用后端 Docker 镜像的 PDFBox 从“2024 年北京空白卷”第 1 页渲染 PNG。页图大小为 209,667 bytes，SHA-256 为 `ec98a93bdcfb3361636f5b31be93e213f1a0dfa1e8ab34b5413332234e4ed788`。该哈希指向 Luna 实际读取的像素文件，不是 PDF 文件哈希。

Luna 的真实视觉请求返回 HTTP 200，耗时 18,458 ms，prompt 3,130 token、completion 166 token、总计 3,296 token（reasoning 88）。模型可见顶层题号为 1–7，判为单栏，并标出第 7 题在页面底部未完、可能跨页；推荐状态为 `PENDING_VISUAL_REVIEW`。这证明视觉审查链路工作，也证明第 7 题不能在没有跨页规则与人工复核时发布。

完整图像 data URL 请求、响应、usage 和页图指标保存于：

`output/gaokao-evidence/2024/luna-2024-visual-page-audit.json`

## 六文件真实入库与数据库核验

入库仅从 `selected-input-manifest` 读取配置选中的 6 个符号链接；其余 8 份 2024 PDF 没有被扫描。Docker 内批处理对 PDF 文本层实际解析并写入 MySQL，import run 为 `0ebc55dd-a5ad-4d8f-8df7-90537ad667e4`。随后使用同一 Docker 网络的 JDBC 查询核验：

| 指标 | 数值 | 含义 |
|---|---:|---|
| discoveredFiles | 6 | 本次清单中实际发现并哈希的 PDF 数量。 |
| excludedFiles | 8 | 2024 目录存在但不属于本次北京/新课标Ⅰ/新课标Ⅱ范围的 PDF 数量。 |
| source rows | 6 | `import_source_file` 的已持久化来源版本数。 |
| occurrence rows | 126 | 从真实 PDF 文本层检测出的顶层题号候选数。 |
| publication rows | 0 | 没有任何候选进入 `canonical_question` 或索引。 |

run 状态为 `PARTIALLY_FAILED`，验证状态为 `VERIFICATION_FAILED`。这两个状态用于准确表达：解析数据已经可恢复地写入，然而页内裁剪、Golden 对比、答案审核与发布都尚未满足，系统不会错误地把 126 个候选当成正式题库。

该 run 的实时数据库核验快照（含 `publishedLinks: 0`）保存于：

`output/gaokao-evidence/2024/database-run-0ebc55dd.json`

## 环境发现

完整 Spring 一次性容器两次均在 Redisson 初始化后以退出码 255 退出，未产生可用的错误堆栈；主 Compose backend 随后成为 healthy。因此批处理使用了同一后端镜像内的 PDFBox/MySQL 驱动的框架无关恢复入口，规避与导入无关的 Redis 启动耦合。此发现保留在报告中，不能作为 Spring 批处理入口已验收的证据。
