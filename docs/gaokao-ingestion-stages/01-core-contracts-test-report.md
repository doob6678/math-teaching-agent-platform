# 阶段 01：核心契约与数据库审计 Schema

状态：已完成。

## 实现

- `PaperTypeRegistry` 覆盖 GAOKAO、ZHONGKAO、MOCK_EXAM、COMPETITION、GENERIC，引用文本只来自结构化可读字段。
- `ImportRunState` 限制两阶段处理顺序，并允许失败后的诚实恢复。
- `QuestionPublicationGate` 只允许官方答案或人工确认的答案发布；未审核 Luna 草稿不可发布。
- `FormulaCanonicalizer` 只做保守格式归一，保留参数差异。
- Flyway `V26` 建立 import、文件版本、规范题、来源出现、不可变审计和模型调用证据表。

## 真实测试

命令：`mvn -q -Dtest=PaperTypeRegistryTest,ImportRunStateTest,QuestionPublicationGateTest,FormulaCanonicalizerTest test`

结果：2026-07-29 Windows 本机 Maven 退出码 `0`。先在类未实现时执行同一组测试，因找不到符号失败；实现后再次执行通过。

## WSL / Docker 部署验证

- WSL `Ubuntu` 是完整运行环境；Windows 不具备 Docker CLI，因此未将其作为部署环境。
- `docker compose up -d --build` 后，MySQL、Redis、RabbitMQ、Milvus、ai-worker、backend 和 frontend 均处于 healthy。
- 后端镜像以 `docker compose build backend` 重建并以 `docker compose up -d --no-deps backend` 滚动重启。
- 后端真实日志记录：Flyway 从 schema V25 执行 `V26__gaokao_question_bank_ingestion.sql`，并输出“Successfully applied 1 migration … now at version v26”。这验证的是实际 Docker MySQL，不是 SQL 文本检查。
- 在 WSL 直接运行 Maven 的测试命令超过两分钟仍未返回（依赖/挂载环境未给出过程输出），因此不把它标记为通过；已有 Windows JUnit 结果只作为允许的轻量验证。

## Luna 约束

本阶段不发起模型请求。Schema 的 `requested_model` 默认值为 `gpt-5.6-luna`，模型调用表完整记录 provider/model/token/状态；后续实验代码必须拒绝非 Luna 模型而非静默回退。
