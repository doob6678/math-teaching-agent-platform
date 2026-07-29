# 阶段 00：当前实现审计

审计日期：2026-07-29。基线 Git 提交为 `51452041`；用户现有未跟踪的 `.local-run/`、DOCX 删除及临时文件未被恢复、删除或纳入本轮改动。

## 已存在能力

- Flyway 版本化迁移、MySQL、Redis、RabbitMQ、Milvus 与本地 Worker 均有项目配置。
- `knowledge_question_bank` 已提供旧题库项、知识点、权限与 BGE 重排入口。
- 教师资料同步已持久化原始页图、文本块和题图解析链路，PDF 讲义使用 XeLaTeX/Noto CJK 与 Windows Poppler 验收规范。
- 环境变量已经配置 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_CHAT_MODEL`、MySQL、Redis、Milvus 与 Worker 连接项；敏感值未记录。

## 明确缺口

- 没有 `PaperTypeRegistry`，也没有以 `paper_type` 驱动的规范题/来源出现双层模型。
- 没有可恢复的 `import_run` 状态机、跨文件配对/去重审计和答案发布门禁。
- 工作区没有发现可用于首轮验收的 2021–2024 真题 PDF/DOCX；因此尚未产生任何真实 Luna 实验、Golden diff、题库发布或三份 PDF。

## 设计决定

新导入域不修改旧 `question_bank_item` 的含义。`canonical_question` 只承载审核后可发布题，`question_source_occurrence` 保存每个原始位置，从而避免重复扫描件污染 Milvus，同时保留可撤销审计。
