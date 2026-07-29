# 阶段 04：入库命令预检

状态：已完成（命令预检；不等同于真实入库完成）。

## 已实现

- `IngestionCommandArguments` 解析 `gaokao:ingest-and-verify --input <目录> --paper-type <类型> [--model <模型>]`。
- `paper-type` 必须显式传入；未知来源只能明确使用 `GENERIC`，不能因为命令名而默认归为 `GAOKAO`。
- `IngestionPreflightService` 在写入 run、渲染页面或调用任何模型之前，对真实输入目录执行文件发现与 SHA-256 身份计算，并构造零工作量进度快照。

## 测试证据

参数、预检、来源身份、进度、类型、状态、公式和答案门禁的核心命令为：

```text
mvn -q -Dtest=IngestionPreflightServiceTest,IngestionCommandArgumentsTest,QuestionOccurrenceIdentityTest,ImportRunProgressTest,IngestionSourceFileDiscovererTest,PaperTypeRegistryTest,ImportRunStateTest,QuestionPublicationGateTest,FormulaCanonicalizerTest test
```

结果：15 项测试、退出码 0。本次仅为 Windows 轻量级验证；无模型调用。

## 尚未完成

当前类提供可复用的命令契约和预检，尚未作为 Spring/Docker 运行器创建持久化 `import_run`。该运行器必须在后续阶段将预检结果写入 V26/V27 表、执行页面渲染和 checkpoint 恢复，才可把命令宣称为完整 `ingest-and-verify`。
