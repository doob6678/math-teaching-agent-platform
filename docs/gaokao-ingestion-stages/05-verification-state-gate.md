# 阶段 05：三层验证状态门禁

状态：已完成（状态机与单元验证；未执行真实 Golden/Luna 审查）。

`ImportVerificationState` 与导入处理状态完全独立。它只允许：

```text
NOT_STARTED → RULE_CHECKING → GOLDEN_COMPARING → AI_REVIEWING → VERIFIED
```

任一活动检查阶段可转为 `VERIFICATION_FAILED`；不能从规则检查直接跳到 VERIFIED，不能在验证失败后把结果静默改为成功。这样即使解析和索引完成，缺少 Golden 或本次 Luna 逐页审查也会阻断发布。

测试：`ImportVerificationStateTest` 已先以缺失类型红灯失败，状态机实现后纳入 17 项核心测试，退出码 0。没有模型调用，故不存在可报告的 Luna 质量结论。
