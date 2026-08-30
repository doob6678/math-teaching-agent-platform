# 当前教师资料切分一致性评测

本报告只统计当前真实库的正例。不同 library、document 和 parser split 不共享 block 指标；历史 documentId/blockId 不参与评分。

- 样本：120 条；split group：76 个。
- 文档规则：Physical FILE document id in top K; legacy ROOT-only hits are not physical-file evidence.
- block 规则：Exact current block id, or expected block inside a same-file hit evidenceBlockIds window, in top K; never mixed across split_group. Exact-only retained as exactBlockRecall.
- 20260830：blockRecall 为窗口口径（精确块或同文件证据窗口命中），exactBlockRecall 另存于 metrics.json。

| 模式 | library | 样本 | doc@1 | doc@3 | doc@5 | block@1 | block@3 | block@5 | avg/P95/P99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| current_specified_library | feishu | 120 | 0.700 | 0.833 | 0.917 | 0.683 | 0.808 | 0.883 | 338.3/436.0/1268.0 |

资源快照已写入 `metrics.json` 和 `runtime.json`，GPU/容器值来自真实运行时采样。


This report was generated from the live HTTP backend and is retained even when gates fail.
