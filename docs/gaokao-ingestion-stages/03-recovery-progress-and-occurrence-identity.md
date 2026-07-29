# 阶段 03：恢复进度与来源出现幂等身份

状态：已完成（不包含视觉切题）。

## 实现与设计

- `ImportRunProgress` 将发现、解析、失败、切题、审核、配对、去重、token 与耗时作为结构化计数；构造时拒绝不可能的文件计数，并从真实发现文件数计算完成百分比。
- `QuestionRegion` 强制非空、非负的页面区域坐标。
- `QuestionOccurrenceIdentity` 用源文件哈希、页码范围、精确区域和原始题号做 SHA-256 指纹。它不把 OCR 文字纳入身份，确保识别结果改善时仍能恢复同一个物理来源出现记录。
- 已部署过的 V26 不能倒改；V27 以追加迁移方式增加 `region_fingerprint`，并把空题号规范为 `''` 后重建唯一键，避免 MySQL 对 UNIQUE 中多个 NULL 的特殊语义放过重复记录。

## TDD 证据

新增测试先因类型缺失而失败；实现后执行：

```text
mvn -q -Dtest=QuestionOccurrenceIdentityTest,ImportRunProgressTest,IngestionSourceFileDiscovererTest,PaperTypeRegistryTest,ImportRunStateTest,QuestionPublicationGateTest,FormulaCanonicalizerTest test
```

结果：11 项测试、退出码 0；这是 Windows 允许的轻量验证。V27 的真实数据库验证由 WSL Docker Flyway 日志单独记录。

## 与后续阶段的关系

视觉切题器只需要提供 `QuestionRegion`、页码和原始题号，就可以以稳定身份写入 occurrence。跨文件配对和去重只消费已有 occurrence，不会以 OCR 文本变化制造重复来源记录。
