# 阶段 09：去重与变式决策门禁

状态：已完成（候选决策规则；尚未对真实跨文件题目运行）。

`QuestionRelationship` 严格限定候选对结果为：`SAME_QUESTION`、`SAME_STEM_DIFFERENT_VARIANT`、`RELATED_BUT_DISTINCT`、`UNDECIDABLE`。`DeduplicationDecisionGate` 只在 `SAME_QUESTION` 且数值、条件、图形、选项、问法和所求目标等确定性字段均无冲突时返回自动合并。变式和不确定关系进入人工审核；相关但不同题明确保持分离。

该门禁保证本地 BGE 只负责召回候选，任何未来 Luna 审查结果也不能单独决定合并或发布。测试先以缺失类型红灯，后与核心测试合计 21 项通过。没有真实候选对、模型调用或 Milvus 索引更新，因此未把它报告为真实去重完成。
