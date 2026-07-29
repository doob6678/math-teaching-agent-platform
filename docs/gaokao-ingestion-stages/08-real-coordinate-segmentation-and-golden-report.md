# 阶段 08：真实坐标切题与 Golden 规则报告

## 设计

`PdfQuestionRegionDetector` 使用 PDFBox `TextPosition` 读取题号首字的真实 PDF 坐标，并由 `QuestionRegionLayoutResolver` 在同一栏位的下一题号处结束区域。它不使用整页占位，也不把模型坐标当作事实来源。若同页同号重复，规则只保留首个锚点，避免文本层回声生成空区域；图像、跨页题和答案仍留给审核。

双栏判断要求左右两侧各至少两个独立顶层锚点；否则降级为单栏。这避免把同高度的两栏题目串成一个区域。相应单元测试覆盖单栏、双栏和同线重复锚点。

## Docker 真实执行数据

在后端 Docker 的 PDFBox 运行时，对 6 份配置指定 PDF 生成 JSONL 坐标文件。统计如下：

| 文件 | 区域数 | 有题号页数 | 重复题号数 | 布局 |
|---|---:|---:|---:|---|
| 北京空白卷 | 21 | 4 | 0 | SINGLE_COLUMN |
| 北京解析卷 | 24 | 14 | 1 | SINGLE_COLUMN |
| 新课标Ⅰ空白卷 | 20 | 4 | 1 | SINGLE_COLUMN |
| 新课标Ⅰ解析卷 | 23 | 13 | 4 | SINGLE_COLUMN |
| 新课标Ⅱ空白卷 | 19 | 4 | 0 | SINGLE_COLUMN |
| 新课标Ⅱ解析卷 | 22 | 14 | 2 | SINGLE_COLUMN |

“区域数”是文本层检测出的顶层题号区域，不等同于发布题数；“重复题号数”是跨页/解析引用或误识别的候选，必须审核。

证据文件位于 `output/gaokao-evidence/2024/regions-*.jsonl`，汇总位于 `region-summary.csv`。

## Golden 规则结果

空白卷题号序列以 `config/gaokao-ingestion-2024.json` 的明文 Golden 期望比对：北京 21/21 通过，新课标Ⅱ 19/19 通过，新课标Ⅰ失败（期望 19、实际 20，多出一个“2”锚点）。因此通过 2 份、失败 1 份；失败禁止发布。

完整实际/期望序列、失败原因和门禁结论保存于：

`output/gaokao-evidence/2024/region-golden-rule-report.json`

## Luna 交叉审查与发现

为调查新课标Ⅰ失败项，Docker 渲染第 2 页 PNG（239,704 bytes，SHA-256 `fee85ffccf8550574c9e66f3d458f0001114ede334357300e3e94765814455db`），并只调用 `gpt-5.6-luna`。HTTP 200，8,981 ms，prompt 3,134、completion 172、total 3,306 token（reasoning 69）。

Luna 可见的顶层题号为 6–11，未包含文本层误识别的“2”；同时指出第 11 题底部不完整、可能跨页。该模型结果不直接修改原始 occurrence，但构成审核队列证据：规则–视觉不一致、跨页风险、禁止发布。

完整请求、data URL、响应和 usage 保存于：

`output/gaokao-evidence/2024/luna-2024-new1-page-2-visual-audit.json`
