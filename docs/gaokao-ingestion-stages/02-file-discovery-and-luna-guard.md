# 阶段 02：文件发现与本次 Luna 实验证据约束

状态：已完成（本阶段不含切题模型实验）。

## 已实现

- `IngestionSourceFileDiscoverer` 递归发现实际 PDF/DOCX，不读取或上传不支持的文件。
- 每个输入通过流式 SHA-256 计算身份，避免大文件整体读入内存；稳定的根目录相对路径排序保证 checkpoint 顺序可重复。
- 本次实验执行记录必须注明 `gpt-5.6-luna`；这是本轮任务执行约束，不修改现有产品的多模型适配与 provider 选择逻辑。

## TDD 证据

首次执行新增测试时类不存在，编译按预期失败。实现后，嵌套 DOCX 的真实临时文件测试发现显示名错误（错误显示为 `nested\\2023.DOCX` 而非文件名）；修复为仅显示 basename，同时内部绝对路径仍保留精确定位。随后执行：

```text
mvn -q -Dtest=IngestionSourceFileDiscovererTest,PaperTypeRegistryTest,ImportRunStateTest,QuestionPublicationGateTest,FormulaCanonicalizerTest test
```

结果：9 项测试、退出码 0。此为允许的 Windows 轻量级单元验证；部署验证仍以 WSL Docker 为准。

## 尚未执行的真实步骤

没有真题文件时，发现器只会返回空列表，不能将此视为解析成功。Luna 切题、公式识别、配对审查、Golden 对比和 PDF 检查仍没有执行或伪造结果。
