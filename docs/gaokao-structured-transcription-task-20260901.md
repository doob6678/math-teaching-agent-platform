# 高考转写去正则化改造 · 子代理任务书（2026-09-01）

## 目标（老板原话转述）

题干转写中打印题号（如"19."）与结构题号（"19题"）重复导致格式问题；题图提取在
一题多图（2022新高考Ⅰ卷第19题两图）时按"图"字+几何比例猜位置，泛化性不足。
要求：**定义全局固定输出格式（题干/答案/解析三个方向 + 按序图片占位标记），
让 LLM 按约定输出结构，管线不再用正则匹配正文来反推结构**。不管什么题、多少道、
数字怎么列、有没有答案解析，都不依赖正则。

## 现状必读文件（先通读再动手）

- 主脚本：`scripts/wsl/run_2024_luna_milvus_ingestion.py`（1564 行，单文件管线）
  - L203 `vision_request`：现有提示词契约（questions 已含 number/text/answer/analysis/figureAnchor）
  - L300 `canonical_question_number` + L74 `CANONICAL_QUESTION_NUMBER_PATTERN`
  - L402 `merge_cross_page_questions`（跨页合并，保留）
  - L459 `load_question_assets`（题图资产清单绑定，保留）
  - L516-621 `_fold_output/_normalize_locator_text/_compact_with_offsets/_question_signature/_locate_question_in_pages`（★删除）
  - L624-763 `SOLUTION_HEADING/_solution_segment_offset/attach_solution_sections`（★删除，从页文本反推解析=正则根因）
  - L766-865 `reconcile_question_numbers_from_page_text/repair_question_number_collisions`（★删除）
  - L868-957 `FIGURE_REFERENCE_PATTERN/_paragraph_bounds/place_question_figures`（★重写为占位标记替换）
  - L960 `publish_canonical_paper`（逐题 md 发布，装配逻辑改模板化）
- 测试：`scripts/wsl/test_run_2024_luna_milvus_ingestion.py`（unittest，无外部依赖，importlib 加载被测脚本）
- 生产配置：`config/gaokao-ingestion-2024.json`（12 卷 selectedFiles；evidenceRoot=output/math-paper-corpus；资产=output/math-paper-assets/gaokao-2024/<试卷名去扩展名>/question-assets.jsonl）
- 背景：`docs/gaokao-ingestion-bottlenecks.md` 第一节（q19 双图修复实录）

## 交付物

新文件（**不得修改上述既有脚本**，生产链路接入由主代理验收后另行执行）：

1. `scripts/wsl/run_gaokao_structured_ingestion.py` —— v2 管线（可从旧脚本复制骨架改造）
2. `config/gaokao-ingestion-structured-test.json` —— **小库配置**：selectedFiles 只放
   `"2022年高考数学试卷（新高考Ⅰ卷）（解析卷）.pdf"`（q19 双图验收锚点），
   `evidenceRoot: "output/test-gaokao-structured-20260901/math-paper-corpus"`，
   collection 一律 `gaokao_math_structured_test`
3. `scripts/wsl/test_run_gaokao_structured_ingestion.py` —— 契约单测（风格对齐现有测试文件）

## v2 输出协议（写进 vision_request 提示词，全英文，response_format=json_object 不变）

每卷每页让模型输出：

```
questions: [{
  number:  "19"                -- 打印题号，纯数字字符串，禁止 "19."/"第19题" 等修饰
  stem:    "..."               -- 仅题干正文：不含题号；引用图片处按阅读顺序嵌入占位标记
                                 [[FIGURE1]]、[[FIGURE2]]...（一题两图=两个标记，按页面
                                 先后编号；无图则无标记）
  answer:  "..."               -- 本页印刷答案原文，无则空串；禁止包含题号或"答案："前缀
  analysis:"..."               -- 本页印刷解析原文，无则空串；禁止题号/解析前缀
  figureCount: 2               -- 整数，必须等于 stem 中 FIGURE 标记数
  latex: [...], continuesToNextPage: bool, confidence: number   -- 沿用
}]
pageText: "..."                -- 整页完整转写（document.md 全文页仍用它），不参与任何结构化反推
```

关键约束（提示词里逐条写死）：
- "number contains ONLY the printed digits, no punctuation or prefix."
- "stem must not start with or contain the question number; the pipeline adds the number heading."
- "Wherever the page shows a figure referenced by the stem, insert [[FIGUREn]] at that exact reading position, numbered from 1 within the question."
- "answer/analysis verbatim from the printed solution sections, WITHOUT question numbers or section labels."
- answer/analysis 可为空（空白卷/无解析页）——这是常态分支，不报错。

## 管线改造规则（确定性装配，零正文正则）

1. 题号：`number` 经 `str.strip()` 后 `isdigit()` 校验（正则 `CANONICAL_QUESTION_NUMBER_PATTERN` 归一化不再需要，改为纯数字校验；非纯数字且非续页 → 丢弃并记日志，与现行行为一致）。同号冲突**不再自动修复**：`canonical_question_records` 保留首条即可（现有函数），删除 reconcile/repair 两个函数。
2. 装配（发布与向量文本共用）：`text = stem` + 若 answer 非空追加 `\n\n【答案】{answer}` + 若 analysis 非空追加 `\n\n【解析】{analysis}`。FIGURE 标记先不展开（向量文本可保留标记原样或删除标记——选择：向量文本中删除标记，发布 md 中替换为图片）。
3. 跨页：`merge_cross_page_questions` 保留；续页 stem 的标记按前一页累计 figureCount **重编号后拼接**（确定性字符串替换，不用正则匹配正文）。
4. 题图绑定（重写 place_question_figures 为 `_embed_figure_markers`）：
   - 资产按 `(pageNumber, bboxPixels.top)` 排序得有序表；
   - stem 中第 k 个 `[[FIGUREk]]` 依次替换为 `![第 N 题图](figures/q-XXX-0K.png)`（与现行发布文件名规则一致）；
   - 校验：标记数 == 资产数。标记多于资产 → 删除多余标记；资产多于标记 → 多余资产按序追加文末（绝不丢图）；零资产 → 不显示图片（符合讲义架构"无有效选择时不显示图片"）；
   - **彻底删除**"图"字段落搜索（FIGURE_REFERENCE_PATTERN）、bbox 比例就近、`_paragraph_bounds`。
5. 删除清单（连同其调用点）：`_locate_question_in_pages`、`_question_signature`、`_compact_with_offsets`、`_normalize_locator_text`、`_fold_output`、`NOTATION_ATOM`、`DASH_FOLD`、`SOLUTION_HEADING`、`_solution_segment_offset`、`attach_solution_sections`、`reconcile_question_numbers_from_page_text`、`repair_question_number_collisions`、`_page_footer_only`、`FIGURE_REFERENCE_PATTERN`、`_paragraph_bounds`、`place_question_figures`（被 `_embed_figure_markers` 取代）、`figureAnchor` 契约字段（被 FIGURE 标记取代）。
   - **保留**：`FRACTION_SLASH_PATTERN`（质量门禁，非结构匹配）、`load_question_assets`、`canonical_question_id`/`canonical_question_records`、发布/向量/Milvus/召回全部基建、run-manifest/evidence 哈希校验、`--finalize-run-id` 恢复语义。
6. metadata 中 `solutionAttached` 改为"本页是否有 answer/analysis 字段"，`textSegments` 仍按页记录（发布 document.md 用），不再承载解析页定位职责。
7. 注释：中文，写明"为什么这么写"（协议取代正则的理由、占位编号约定、fail-closed 边界），对齐仓库风格。

## 测试要求（先跑通单测再做真实转写）

单测覆盖（全离线，不起 Docker/Milvus/模型）：
- 协议解析：纯数字 number 接受；"19."/"第19题" 在 v2 契约下应被拒为续页丢弃（断言行为并写注释）；
- 装配模板：无答案/无解析/两者皆有 三分支；正文中不出现打印题号；
- FIGURE 标记：单图、双图按序替换到正确位置；标记数≠资产数两个方向的兜底；
- 跨页合并 + 续页标记重编号；
- 同号冲突保留首条、不自动改号；
- `response_format` 请求体内 prompt 含协议关键词（number 纯数字、FIGURE 约定）的快照断言。

运行：`python scripts/wsl/test_run_gaokao_structured_ingestion.py`（Git Bash 本机即可，勿在 WSL 里绕）。

## 真实转写小库验收样本（单测绿了之后）

- 环境：Windows Git Bash，Docker 在 WSL。视觉调用复用现有 bridge 机制（`docker exec -i <container> python -c ...`，容器名与密钥取 `.env`：`OPENAI_API_KEY/OPENAI_BASE_URL/MATH_AGENT_VISION_BRIDGE_CONTAINER`，先检查 .env 里已有配置，勿新造）。若 bridge 容器不可用，如实报告并用 `wsl docker ps` 诊断，**不要**为跑通而 mock 模型响应——禁止伪造验证。
- 执行：v2 脚本 + test config，`--collection gaokao_math_structured_test`，evidence-root 走 test 路径；为控制成本可只转写 q19 所在页与相邻页（脚本若不支持选页，加一个 `--only-source/--only-pages` 调试参数并说明）。
- 若本机 embedding worker（127.0.0.1:8092）/Milvus（127.0.0.1:19531）在跑则真实入库 test collection；不在跑则停在发布目录并如实报告（发布目录+单测是本任务硬验收项，向量入库是软项）。
- 产出比对：test 小库 `questions/q-019.md` 对比生产 `output/math-paper-corpus/2022年高考数学试卷（新高考Ⅰ卷）（解析卷）/questions/q-019.md`，逐项目标：两图各插在正确"如图"位置、正文无重复题号、答案/解析完整且无前缀堆叠。

## 红线

- 只写上述 3 个新文件（+可选调试参数）；不修改 `run_2024_luna_milvus_ingestion.py`、生产 config、生产 corpus 目录、`gaokao_math` collection、MySQL/Redis、他人正在改的文件（改前先读）。
- 禁止删除/清理任何 Docker、pip、模型、转写 evidence 缓存；禁止 prune。
- 禁止把图片二进制/base64 传给任何非视觉转写调用；visual bridge 单页请求沿用现行 data_url 机制，不新增发图路径。
- 禁止 mock/伪造模型响应或测试通过；跑不动的项如实标注。
- 结束时落盘一份 `output/test-gaokao-structured-20260901/REPORT.md`：文件清单、单测输出摘要、真实转写 q19 比对结论（含 q-019.md 关键片段原文）、遗留问题。
