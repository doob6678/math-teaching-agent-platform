# 高考题库入库侧精确去重与扩展验收（2026-08-26）

## 目标与边界

本次改造把题库去重放在 canonical 入库脚本中。唯一判定键是：

```text
(sourceSha256, canonical numeric questionNumber)
```

Milvus 最终题目主键为：

```text
UUIDv5(URL, "question\\n" + sourceSha256 + "\\n" + numericQuestionNumber)
```

该键不包含页码、跨页范围、题干、答案、解析、provider 或知识点关键词。因此同一来源的不同题号始终保留，文本补全和解析挂载不会制造新题目身份。本轮不做语义相似、题干相似、答案相同或关键词相同的合并。

## 入库顺序

1. 读取配置白名单来源并流式计算 PDF SHA-256。
2. Terra 页级 evidence 产生结构化题目和完整 `pageText`。
3. 先合并跨页题，再执行可唯一推断的题号碰撞修复。
4. 按 `(sourceSha256, questionNumber)` 选择最早页码记录，统计 `duplicateSkippedCount`。
5. 对最终题目挂载答案/解析；没有答案的题目保持合法且不删除。
6. 生成稳定题目 ID，发布完整来源 Markdown、逐题 Markdown 和 manifest。
7. embedding 使用 10 条有界批次；Milvus 使用默认 100 条有界 upsert 批次，按批重试，末尾只 flush 一次。

## 历史清理门禁

确定性 upsert 不能移除旧 UUID。清理因此只在 `gaokao_math` 中执行，并只使用当前运行的 canonical replacement 白名单来源。每个来源先通过服务端 `entities/query` 的 `count(*)` 过滤预检，再用相同的 `sourceFile/documentFullName` JSON 过滤删除；不查询或载入全量实体，不逐条查询 Milvus，不触碰飞书、教师资料或教材 collection。

没有 canonical replacement 的来源禁止直接删除。必须先完成该来源的 PDF、Terra 页级 evidence、授权题图资产、manifest 和逐题 Markdown，再把它加入 replacement 批次。这样新年份、新试卷或其它题库可以复用同一清理与稳定主键流程，不依赖年份、题号、关键词或测试数据分支。

## 本次真实证据

- 未去重基线：`output/acceptance/knowledge-point-recall-baseline-pre-dedup-20260826.json`
  - overall applicable MRR `0.288889`
  - topic-only MRR `0.37037`
  - 抛物线 topic-only MRR `0.111111`，canonical gold 首次在第 9 名
  - 原始窗口有 2 个重复来源+题号键、6 行额外副本，最大占位 4
- 只读预检：`output/acceptance/gaokao-ingestion-preflight-20260826.json`
  - `gaokao_math` 存在
  - 12 个白名单来源均使用服务端 JSON 过滤统计
  - 没有全量实体加载
- 真实 replacement 入库：`output/math-paper-transcription-runs/terra-gaokao-20260820T232559Z-975d2574-report.json`
  - 12 份来源、250 道题、12 份全文
  - 清理 741 行，写入 262 条
  - `collisionCount=2`，`duplicateSkippedCount=0`
  - upsert 批次 `100`，共 3 批，最终 flush 1 次
  - `clientFullCollectionLoad=false`
- canonical manifest 复核：12 份 manifest、250 道题，`(sourceSha256, questionNumber)` 重复键为 0。
- 去重后真实召回：`output/acceptance/knowledge-point-recall-report-after-exact-dedup-20260826.json`
  - overall MRR `0.575`，高于基线 `0.288889`
  - topic-only MRR `0.75`，高于基线 `0.37037`
  - 当前 replacement 覆盖的 12 份来源不再产生重复主键
  - 报告仍为 `asset_contract_failed`：北京空白卷/解析卷和辽宁联考历史行未被当前 12 份 canonical replacement 覆盖；北京旧 UUID 仍在原始窗口中形成同来源+题号重复

## 验收结论

本次“入库侧精确主键去重”实现、批处理、来源限定清理和 MRR 改善已由真实运行验证。全 `gaokao_math` 资产验收不能标记通过，原因是 collection 中仍存在未覆盖来源的历史行；这不是本轮 12 来源 replacement 可以安全删除的范围。下一扩展批次必须先为这些来源发布 canonical replacement，随后复用同一来源过滤清理，直到原始 Top-10 的来源+题号唯一且资产契约通过。
