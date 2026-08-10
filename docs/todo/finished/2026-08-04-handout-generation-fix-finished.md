# 2026-08-04 讲义生成问题调查、修复与验收完成记录

> 类型：讲义生成质量、架构、消息队列、性能、时延、Token、成本、进度、测试验收
>
> 状态：本轮代码修复和真实验收已完成；本文同时保留尚未完成的生产化 TODO，不把建议目标写成已达成事实。

## 1. 结论

当前讲义生成系统已经具备真实模型、多阶段编排、RabbitMQ 异步 Worker、MySQL 状态持久化、Redis 并发控制、真实 XeLaTeX/PDF 导出和版式审计，工程化水平明显高于 Demo。

但目前还不能认定为一流水平的生产系统，原因有四类：

1. 内容质量已经通过本轮三种产物的硬门禁，但独立内容审校 Agent 和学生安全审校 Agent 尚未形成完整闭环。
2. RabbitMQ、ACK、租约、幂等和恢复链路已经接入，但缺少完整的 queue wait、lease wait、重复投递率和 P95/P99 观测，不能只看模型耗时判断系统性能。
3. Token 已能记录，当前 Trace 的 `actualCost=-1`、`costKnown=false`，真实 Provider 价格账本尚未接入，不能把 `estimatedCost` 当作货币成本。
4. 临时导出目录中的图片已确认回收；最终 PDF 没有嵌入通用资源图片；但持久化视觉上传资产在资源阶段结束后的完整生命周期回收，仍需补充并验证。

## 2. 修复前的讲义设计问题

本轮重点针对 `real-api-lecture-16-10.pdf` 暴露的问题，并同步检查教师版、学生版。

### 2.1 16:10 版本的问题

修复前的 16:10 产物存在以下设计矛盾：

- 只保留了单题或截取局部内容，没有保留完整四题及题目顺序，课堂投影无法形成完整教学闭环。
- 题目、知识网络、总结和资料图片混在一起，投影页出现资源卡、证据卡和授权资料的内部信息。
- 把 2008 年试卷整页图当成讲义内容输出，图片没有题目绑定关系，也不适合作为课堂投影主体。
- 出现横线、填空、`<wait>`、Markdown 表格、内部日志字段等面向生成过程的内容。
- 产生空白重复页或无教学内容的页面，页数与内容顺序不稳定。
- 曾出现标题存在但题目主体缺失的“单页讲义”形态，无法支持教师讲解和学生跟随。

### 2.2 为什么 16:10 不能强行做成单页

16:10 是课堂投影比例，不等于“所有内容压缩到一页”。本次输入包含四道完整题、知识网络、方法提示、易错检查和总结回顾；如果压成单页，会造成字号、行距和题目步骤不可读，且无法保持“知识定位→题目 1→题目 2→题目 3→题目 4→总结”的课堂节奏。

因此 16:10 版本应采用“每页一个稳定教学单元”的分页策略，允许一个题目因内容长度跨页，但不能拆散题干、方法和课堂检查点。最新真实产物实际为 12 页，页面均为 `921.6 × 576`，保留完整知识网络、四道题和总结；页数不是质量目标，内容完整和可读性才是。

这里的“纯空白”必须区分两种情况：

- 应保留：学生练习所需的空白作答区域，以及投影中用于留白和分隔的真实白空间；它们属于教学设计。
- 必须删除：没有题目、知识点或教学用途的空白重复页，以及导出过程残留的空白占位页。

最终 16:10 版本保留完整题目和知识网络，同时删除无意义空白重复页；投影版不生成横线、填空线或 `<wait>`，需要课堂书写时只使用题目旁的纯白区域；学生版保留作答空白，教师版保留完整讲解。

### 2.3 其他前置质量问题

- 通用 `资料图片` 没有题目绑定时不应进入教师版、学生版或投影版。
- 视觉上传的页面图是检索/证据输入，不是讲义正文；除非图片明确绑定到题目且版式审计允许，否则不能输出到最终讲义。
- 模型输出中的内部协议字段、调试日志、资源 ID、等待标记和 Markdown 表格必须在导出前清理。
- JSON 解析不能依赖模型“自行修正”所有格式问题，否则会增加重试、Token 和时延，并且可能把错误内容带入排版。

## 3. 本轮已完成的修复

- 16:10 版本从单题修复为完整保留 4 道题及顺序。
- 删除资源卡、证据卡、2008 年试卷整页图片和空白重复页。
- 删除投影中的横线、填空、`<wait>`、Markdown 表格和内部日志。
- 教师版、学生版删除无题目绑定的通用 `资料图片`，题目绑定图片仍按权限和版式规则处理。
- RAG 查询范围从狭窄的“二次函数”扩展到函数定义域、值域、单调性、最值、参数，以及空间向量、线面角。
- JSON 解析优先使用代码清洗和结构化解析，模型只作为兜底修复；最新验收没有 JSON 修复重试。
- JSON 修复后必须重新验证题目数量、题目顺序、必填字段、答案/提示的非空语义和题目-证据绑定，任何“解析成功但内容为空”的对象都必须拒绝导出。
- Python 迁移路径新增确定性 JSON 归一化：去 BOM、代码围栏和外围说明，兼容 `lectureCards` 字符串数组，过滤资源卡和内部字段；Writer 节点先做题目覆盖、顺序、非空和受众禁用内容校验，只有失败时才发起一次最小上下文 repair，最终验证阶段只拒绝、不再调用模型。
- 修复 XeLaTeX 中 CJK 数学文本的双反斜杠 bug；`\text{结构识别}` 现在按 TeX 命令渲染，不再在 PDF 中显示为 `text 结构识别`。
- RabbitMQ Worker、MySQL 状态持久化、幂等、ACK、租约、有限重试、恢复和 Redis 并发控制已接入。
- Python 讲义异步启动和 resume 已改为创建一个 `PythonHandoutAgent/python_handout` RabbitMQ 任务；Worker 在 MySQL lease 下只调用一次 Python Graph，结果和 usage 写入后才 ACK，重复投递不会重复发布。
- Python checkpoint resume 会跳过已成功的资料整理和 Writer 节点；只对缺失节点调用模型，最终结构/语义门禁仍完整重跑。
- Python 节点 Trace 补齐 provider、model 和 `costKnown`；未配置价格时使用 `-1` 未知成本哨兵，不把未知成本当作 0。
- 后端已重新 Maven 编译，JAR 已覆盖到运行中的 Docker backend，容器健康。

## 4. 完整测试题与输出顺序

最新真实验收使用四道完整题，三个最终版本都遵守以下顺序：

1. **题目 1：定义域**

   已知函数
   \[
   f(x)=\frac{\sqrt{x+1}}{x-2},
   \]
   求定义域。

2. **题目 2：基本不等式求最值**

   已知函数
   \[
   g(x)=x+\frac{1}{x},\quad x>0,
   \]
   求其最小值。

3. **题目 3：二次函数区间最值与参数**

   已知函数
   \[
   h(x)=x^2-2ax+1
   \]
   在区间 \([0,2]\) 上的最小值为 \(-3\)，求实数 \(a\)。

4. **题目 4：正方体中的线面角**

   在正方体 \(ABCD-A_1B_1C_1D_1\) 中，求直线 \(AC_1\) 与平面 \(A_1BD\) 所成角的正弦值。

教师版包含完整讲解、答案、评分点、板书序列、易错点和迁移；学生版包含题目、知识点、前置知识、分步提示、易错自查和作答空白；16:10 版包含知识网络、四道题的课堂检查点和总结回顾。题目 1 至题目 3 的授权材料存在对应证据缺口，最终内容已明确标注，不能伪称为直接教材原文；题目 4 有授权教材与教师资料支撑。

### 4.1 测试使用的完整原始题目数据

Python 图契约测试和最新真实验收使用同一组四题输入；归一化测试不再用单题或仅有 `lecture` 字符串的缩减 fixture。下面保留实际传入 `questionText` 的完整内容，便于后续迁移 Java/Python 链路时逐字复现：

```text
【题目 1】
已知函数 f(x)=sqrt(x+1)/(x-2)，求定义域。
【题目 2】
已知函数 g(x)=x+1/x（x>0），求最小值。
【题目 3】
函数 h(x)=x^2-2ax+1 在区间[0,2]上的最小值为-3，求实数a。
【题目 4】
正方体 ABCD-A_1B_1C_1D_1 中，求 AC_1 与平面 A_1BD 所成角。
```

归一化测试还实际覆盖了四个 `question` card 按 1→2→3→4 顺序投影、`resource` card 被过滤、课堂投影留白标记由渲染器生成，以及缺少任一题时拒绝导出的语义门禁。该测试数据与本节 PDF 验收题目保持一致，不能用只验证题目数量的空内容样本替代。

## 5. 推荐的正确架构

### 5.1 当前实际 DAG

```text
workflow 创建
  -> resource_curation
       -> teacher_writer  -+
       -> student_writer  -+-> stage 汇总
       -> lecture_writer  -+      -> 内容/权限/格式门禁
                                  -> 教师版、学生版、16:10 渲染
                                  -> XeLaTeX 编译
                                  -> PDF/PNG/布局审计
                                  -> 最终状态与产物持久化
```

`teacher_writer`、`student_writer`、`lecture_writer` 在资料整理完成后并行，彼此不依赖；汇总和导出等待三者全部成功。旧的 `template_selection`、`outline_planning`、`source_review`、`student_safety_review`、`layout_review`、`merge_coordinator` 目前不是当前真实执行阶段，只保留兼容契约，因此独立审校能力不能被误报为已经在线。

### 5.2 RabbitMQ 的职责

RabbitMQ 应继续使用，负责：

- 异步调度和削峰，避免 HTTP 请求长期占用。
- 按阶段隔离资源，分别控制资料整理、教师版、学生版、投影版、渲染和导出并发。
- 控制 prefetch、优先级、租户并发和 Provider QPS/TPM，避免单租户占满 Worker。
- 对可重试错误做延迟重投递，对不可恢复错误进入死信队列。
- 在 Worker 崩溃时通过未 ACK 消息和租约恢复任务。

RabbitMQ **不负责**：

- 作为 workflow 状态真相；消息本身不能代表最终成功。
- 保存完整讲义、Token、成本账本或质量审计结果。
- 提供跨消息的幂等一致性；重复消息仍必须由数据库幂等键和 Worker 检查处理。
- 解决模型上下文过大、模型输出过长、JSON 错误或 Provider 尾延迟。
- 替代 MySQL 的版本控制、CAS/行锁、租约 fencing 或最终产物校验。

所以“为什么不用消息队列”的答案是：应该用，但队列只解决调度和可靠投递；状态、幂等、成本和产物一致性必须由 MySQL 与确定性门禁共同完成。“性能问题”也不能简单归因于 RabbitMQ；当前更大的单任务瓶颈是模型输入、输出和修复重试，但没有 queue wait 指标前不能排除队列积压。

### 5.3 MySQL 状态真相、ACK、幂等和恢复

正确的提交顺序应为：

```text
取消息
  -> 以 workflow_id + stage_code + attempt 获取/校验租约
  -> 检查 stage 是否已成功
  -> 调用检索或模型
  -> 代码清洗、结构校验、质量门禁
  -> 按 stage 幂等写入结果、Token、成本、耗时和状态
  -> 原子更新 workflow 快照/版本
  -> 成功持久化后 ACK
```

必须满足以下规则：

- ACK 只能发生在结果、Token、成本（若已知）和状态都成功落库之后。
- 重复消费先查 stage 幂等键；已完成则返回已持久化结果，不重复调用模型、不重复计费。
- Worker 持有带过期时间的 lease；长模型请求需要续租或使用 fencing token，防止旧 Worker 在失去所有权后覆盖新结果。
- workflow 快照需要 revision/CAS 或等价原子合并，不能依赖单机 `synchronized` 解决多实例覆盖。
- Worker 崩溃、进程重启或租约过期时，任务可从 MySQL 状态恢复；达到重试上限后进入失败或死信，不可无限重试。

当前实现已接入 MySQL、ACK、幂等、租约、重试和恢复，但多实例快照版本覆盖、长请求租约续租和重复计费仍应增加真实集成测试和告警。

## 6. 进度模型

### 6.1 当前进度

当前进度以持久化 workflow 快照和 SSE 变化推送为基础，并按状态指纹去重。能展示阶段完成、失败和恢复，但仍偏离散阶段百分比。

最新真实 workflow `bf9f461c-5ebf-43fe-bcb6-3f10d1d0ad4d` 的阶段状态为：

| 阶段 | 状态 | 执行关系 | JSON 修复重试 |
|---|---|---|---:|
| `resource_curation` | 成功 | 首阶段 | 0 |
| `teacher_writer` | 成功 | 与另外两个 writer 并行 | 0 |
| `student_writer` | 成功 | 与另外两个 writer 并行 | 0 |
| `lecture_writer` | 成功 | 与另外两个 writer 并行 | 0 |
| 汇总/导出 | 成功 | 三个 writer 完成后执行 | 0 |

### 6.2 应补充的真实进度事件

进度不能把“已发送模型请求”伪装成“已完成”。建议事件粒度为：

```text
queued
queue_wait
lease_acquired
lease_wait
retrieval
resource_curation
writer_model
json_parse
json_repair
retry_wait
render_queue_wait
xelatex_compile
pdf_render
layout_audit
export
persisted
completed / failed
```

每个事件应带 `workflowId`、`stageCode`、`attempt`、开始/结束时间、revision、错误分类、输入/输出 Token 和成本状态，并可恢复、去重、查询历史。当前缺少可靠的 queue wait、lease wait、render/export 分段，以及任务级 P50/P95/P99；这属于必须补齐的生产化 TODO。

## 7. 最新真实时延与性能

本次四阶段使用显式模型 `openai/gpt-5.6-terra`，没有 JSON 修复重试。三个 writer 在资料整理后并行，已持久化的可比较事实是 `teacher_writer` 模型调用 109,610 ms；Trace 没有完整记录端到端 queue/render/export 分段，因此不把阶段耗时相加冒充用户端到端时延。

| 阶段 | Prompt Token | Completion Token | Total Token | Model ms |
|---|---:|---:|---:|---:|
| `resource_curation` | 8,068 | 925 | 8,993 | 21,675 |
| `student_writer` | 6,775 | 2,901 | 9,676 | 53,268 |
| `lecture_writer` | 6,869 | 2,533 | 9,402 | 47,382 |
| `teacher_writer` | 9,149 | 5,972 | 15,121 | 109,610 |
| **合计** | **30,861** | **12,331** | **43,192** | **231,935** |

`model ms` 的阶段合计大于端到端时长，是因为三个 writer 并行；不能把阶段耗时相加当成用户等待时间。端到端时延还包含排队、租约、状态写入、汇总、渲染、编译、PNG 和审计等未完整拆分的时间。

历史真实 Trace 也显示模型尾延迟和 JSON 重试会显著放大成本与等待：

| 逻辑运行组 | Prompt | Completion | Total | 主要观察 |
|---|---:|---:|---:|---|
| A | 48,607 | 9,837 | 58,444 | `student_writer`、`lecture_writer` 各 1 次修复 |
| B | 61,798 | 11,408 | 73,206 | `student_writer` 117,201 ms，2 次修复 |
| C | 45,200 | 7,652 | 52,852 | `student_writer` 78,208 ms，1 次修复 |
| D | 44,517 | 10,434 | 54,951 | `lecture_writer` 1 次修复 |

因此性能修复顺序应为：先压缩资料包和重复上下文，再限制每阶段输出和 repair 预算，随后补齐队列指标并按真实瓶颈调度 Worker；不能只增加 Worker 数量，否则会放大 Provider 并发、Token 和成本。

建议生产指标：普通任务端到端 P95 < 90 秒、复杂任务 P95 < 180 秒、queue wait P95 < 2 秒、JSON repair rate < 5%、重复执行率为 0、Token 超预算率为 0、死信率 < 0.1%。这些是分档目标，不是当前已达成数据。

## 8. Token 与成本控制

### 8.1 已有控制

- 每个阶段记录 Prompt、Completion、Total Token。
- 资料整理先于三个 writer，允许 writer 共享压缩后的资料包。
- 代码优先清洗和解析 JSON，减少无意义完整上下文重试。
- Redis 做并发控制，RabbitMQ 做削峰和重试隔离。
- 质量门禁和格式清洗在导出前阻断坏内容，避免坏结果继续进入渲染链。

### 8.2 必须继续修复

- 为 `resource_curation`、`teacher_writer`、`student_writer`、`lecture_writer` 分别配置输入上下文上限和输出上限。
- 发送前按优先级删除重复资料、低相关片段和重复提示；优先保留题目直接证据、关键公式和题目映射。
- JSON repair 只携带错误字段、错误位置和最小必要上下文，不重新发送整份资料包。
- retry、repair 和降级调用必须共享 workflow 预算，并记录每次调用的原因。
- 缓存必须包含租户、资料版本、题目版本、Prompt 版本、模型和样式版本；输入版本变化必须失效。
- 价格表按 Provider/Model 区分输入、输出和缓存 Token，保存 `pricingVersion`、币种和账本版本。

### 8.3 当前成本事实

最新 Trace 的四个阶段均为：

```text
actualCost = -1
costKnown = false
```

这表示当前系统知道 Token 数，但不知道经过 Provider 真实价格换算后的货币成本。历史 Trace 中出现的 `estimatedCost=0.48` 也只有估算语义，不能当作真实费用。没有价格配置前，系统必须显示“成本未知”或“仅 Token 估算”，不能伪造人民币、美元或其他货币金额。

达到生产要求后，每次调用至少要持久化：`provider`、`model`、输入 Token、输出 Token、缓存 Token、价格版本、输入成本、输出成本、总成本、币种、重试原因和 workflow/stage 归属；预算超限要在发起模型请求前阻断。

## 9. 图片和资源生命周期

资源策略必须遵守三条边界：

1. 页面视觉上传图用于检索和证据判读，不自动等于讲义配图。
2. 只有与具体题目绑定、权限允许、分辨率和版式审计通过的图片，才可进入对应题目内容。
3. 资源整理阶段结束后，不再需要的临时资源应回收；最终导出过程使用的临时复制文件必须在导出完成后删除。

已验证事实：

- 最新教师版、学生版、16:10 版最终 PDF 均无嵌入图片。
- XeLaTeX 导出使用临时目录，临时复制的图片在导出结束后删除。
- 通用资源图片、资源卡、2008 年试卷整页图没有进入最新三份讲义。

仍未充分验证的事实：持久化视觉上传资产本身是否在 `resource_curation` 完成后按引用计数、TTL、失败回收和任务取消规则完整清理。该项需要补充显式状态机、引用关系、定时清理和集成测试，文档不把临时目录回收冒充为持久化资产全生命周期已完成。

## 10. 最新三份 PDF 证据

证据目录：

`output/mcp-acceptance/mcp-luna-handout-20260804T173833Z`

| 产物 | 页数 | 页面尺寸 | 字节数 | SHA-256 |
|---|---:|---|---:|---|
| 教师版 | 6 | A4 (`595.28 × 841.89`) | 135,553 | `0f88c6fa07e9561ae03cf55bee360a6bbe510edb177220e6edc33db4e6e7a12f` |
| 学生版 | 4 | A4 (`595.28 × 841.89`) | 118,898 | `d75cfe078c69a3d7aacec58cea452760e16c2dbc2983258ba6670bd38c0f988b` |
| 16:10 课堂投影版 | 12 | `921.6 × 576` | 101,828 | `a4b85b3c76974d59e17239c0191197b5b40ec44cced1e18740ea8efb315262fc` |

PDF 文本读取已确认三套产物的四道题关键标记 `定义域`、`最小值`、`实数 a`、`正方体`、`AC` 和 `平面` 均存在；16:10 文本未发现 `<wait>`、`资料依据`、`完整解答`、`---`、日志、资源卡或 `TEACHER_IMAGE`。三份最终 PDF 均无嵌入图片。Windows Poppler 实际生成并通过 .NET 文件计数的 PNG 为教师 6、学生 4、投影 12；旧 `acceptance-summary.json` 曾记录 1、1、3，属于验收脚本计数 bug，不能作为页数证据。

### 10.1 修复后重新导出的最终证据

修复 CJK 数学文本后，使用同一个已完成 workflow `bf9f461c-5ebf-43fe-bcb6-3f10d1d0ad4d` 重新执行三个 PDF export，没有重新调用模型。`pypdf` 读取结果如下：

| 修复后产物 | 页数 | 页面尺寸 | 文本字符数 | 字节数 | SHA-256 | 四题标记 | 图片嵌入 |
|---|---:|---|---:|---:|---|---|---:|
| `post-fix-teacher.pdf` | 6 | A4 (`595.28 × 841.89`) | 6,060 | 135,553 | `50de70b3d2eb3cbf554bd9464bed3e50b5f3a159961aaf4e38832f1dea8ca013` | 是 | 0 |
| `post-fix-student.pdf` | 4 | A4 (`595.28 × 841.89`) | 3,050 | 118,898 | `1003c67e2f9320f06be86f56d9ba711fd7b3c4ab00c6706fda5adf08835b5567` | 是 | 0 |
| `post-fix-lecture.pdf` | 12 | `921.6 × 576` | 2,358 | 101,439 | `0fba5bc80a4bbd514b43cad1832203f00e89e6202f9f88c5d1e63871df92b6f2` | 是 | 0 |

16:10 修复后文本检查：`<wait>`、`---`、`TEACHER_IMAGE`、`资源卡`、`内部日志` 均不存在；`text 结构识别` 和 `text 约束锁定` 均不存在，正常文本 `结构识别`、`约束锁定`、`方法匹配` 均存在。PNG 视觉复核确认知识地图页、先备知识页、四道题/方法卡和总结页均为题目内容加真实纯白空间，没有横线或填空线。

## 11. 测试与真实验收

已真实运行并通过：

- `FormulaMarkupSanitizerTest`：16 通过。
- `AgentRunPlanServiceTest`：7 通过。
- `MultiAgentWritingArtifactExportServiceTest`：8 个执行用例通过，另有 1 个跳过。
- `MultiAgentWritingServiceTest`、`WritingEvidenceContextFormatterTest`、`TeacherResourceUploadStagingCleanupTest`：已加入讲义上下文、数组内容、证据过滤和临时资源清理覆盖。
- 历史讲义相关定向测试：37 通过、0 失败、1 跳过。
- 最新真实 MCP workflow：四阶段成功，显式 `openai/gpt-5.6-terra`，无 JSON 修复重试。
- 三份 PDF 文件、页数/尺寸、逐页 PNG、文本提取和图片嵌入检查均通过；视觉布局仍需在发布前由真实 PNG 人工复核，不能仅凭 PDF 文本门禁代替。

本轮实际命令及结果：

```text
mvn -q -Dtest=FormulaMarkupSanitizerTest,AgentRunPlanServiceTest,MultiAgentWritingArtifactExportServiceTest,MultiAgentWritingServiceTest,WritingEvidenceContextFormatterTest,TeacherResourceUploadStagingCleanupTest test -> exit 0
mvn -q -DskipTests compile -> exit 0
python3 -m py_compile ai-worker-python/app/agent_runtime.py ai-worker-python/app/server.py ai-worker-python/app/settings.py ai-worker-python/app/streaming_runtime.py ai-worker-python/app/handout_runtime.py -> exit 0
健康的 `math-agent-rag-ai-worker-1` 容器真实依赖中运行 `test_handout_runtime` -> 6 tests、0 failures、0 errors；图拓扑、三文档生成、JSON 归一化、资源卡过滤、题目顺序/语义覆盖、缺题拒绝，以及两个 resume 场景均通过，其中端到端图契约、归一化和 resume 场景使用完整四题数据；JSON 解析优先级和列表字段投影保留为针对性单元 fixture。
```

本轮补齐完整四题 fixture 后，容器内实际复核命令为：

```text
docker cp ai-worker-python/app/handout_runtime.py math-agent-rag-ai-worker-1:/tmp/current-app/app/handout_runtime.py
docker cp ai-worker-python/tests/test_handout_runtime.py math-agent-rag-ai-worker-1:/tmp/test_handout_runtime.py
docker exec -e PYTHONPATH=/tmp/current-app:/app math-agent-rag-ai-worker-1 sh -lc 'python3 /tmp/test_handout_runtime.py'
-> Ran 6 tests in 0.791s; OK
```

其中 `test_resume_skips_checkpointed_evidence_and_writer` 的 checkpoint 包含四道完整题和完整教师版，真实断言证据检索不调用 Java、教师版不重复调用模型，并且仅调用 `student_writer`、`lecture_writer`；`test_resume_reuses_complete_nodes_without_model_or_broker_call` 则验证三份完整 Writer checkpoint 全部复用。

同一运行容器的真实 CUDA 检查结果为：`cuda_available=True`，设备为 `NVIDIA GeForce RTX 5060 Laptop GPU`。这只证明当前 worker 的 GPU 依赖和本轮 Python 讲义契约测试可运行，不代表全量 Python 测试已经通过；全量测试限制仍按下文记录。

Python 测试状态必须单独看待：主机 `/usr/bin/python3` 没有 `pytest`、FastAPI 和 torch；健康的 ai-worker 镜像也没有安装 pytest，且测试目录不在镜像中。因此主机 `pytest` 无法启动，主机 `unittest discover` 实际执行 40 个测试时有 4 个依赖导入错误和 1 个既有 embedding 错误；本轮只将当前 `test_handout_runtime` 复制到健康容器的临时目录执行，6 个定向测试通过，不能宣称 Python 全量测试通过。Python 迁移完成后必须在包含项目测试和真实 GPU 依赖的 CI/worker 镜像中重新执行。

不能宣称全量 Maven 测试通过。历史全量结果为：

```text
756 tests
52 failures
15 errors
8 skipped
```

已知失败主要集中在 Windows 中文编码、XeLaTeX/Noto CJK 环境差异、旧检索排序断言，以及测试数据与异步时序耦合。后续必须分类治理全量失败，不能用定向测试结果代替全量通过。

## 12. 后续 TODO 清单

- [ ] 补齐 workflow/stage 的 revision/CAS 或等价原子合并，并增加两个以上 Worker 并发更新集成测试。
- [ ] 校准最大 Provider timeout、重试总时长和 lease，或实现可靠 lease 续租与 fencing token。
- [ ] 增加 queue wait、lease wait、retry、render、export、total 的 P50/P95/P99、积压量、ACK 延迟和重复投递率。
- [ ] 接入真实 Provider 价格账本，保存价格版本、币种和阶段/任务成本；预算超限前阻断请求。
- [ ] 为四个模型阶段设置独立输入/输出/repair/retry Token 预算，并验证长资料和复杂几何题。
- [ ] 持久化视觉资产增加引用、TTL、取消任务、失败任务和定时清理策略，并做真实生命周期验收。
- [ ] 恢复或明确裁剪独立内容审校、学生安全审校和数学答案一致性审校闭环。
- [ ] 统一主教学 DAG 与多智能体写作链路的 workflow、stage、事件、Token、成本和质量门禁模型。
- [ ] 治理全量 Maven 测试失败，统一真实 XeLaTeX、字体和 Windows/Linux 测试环境。

## 13. 相关证据和源码

- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingArtifactExportService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/worker/AgentWorkerTaskConsumer.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/worker/AgentWorkerTaskStore.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MyBatisMultiAgentWritingWorkflowStore.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/RedissonAgentConcurrencyGuard.java`
- `backend-java/src/main/java/com/doob/mathagent/infrastructure/text/FormulaMarkupSanitizer.java`
- `backend-java/src/test/java/com/doob/mathagent/agent/MultiAgentWritingArtifactExportServiceTest.java`
- `backend-java/src/test/java/com/doob/mathagent/infrastructure/text/FormulaMarkupSanitizerTest.java`
- `ai-worker-python/app/handout_runtime.py`
- `ai-worker-python/tests/test_handout_runtime.py`
- `scripts/local/audit_handout_layout.py`
- `output/mcp-acceptance/mcp-luna-handout-20260804T173833Z/trace.json`
- `output/mcp-acceptance/mcp-luna-handout-20260804T173833Z/acceptance-summary.json`

## 14. Python AI 模块迁移交接设计

Java AI 模块即将迁移到 Python 时，不能只把模型 HTTP 调用搬过去；必须保留现有 workflow 的外部契约和质量门禁。迁移期间以消息和数据库契约为边界，避免 Java、Python 各自重复发起模型请求。

### 14.1 Python 服务职责

- 消费 RabbitMQ 的 `workflow_id`、`stage_code`、`attempt`、租约/fencing token、输入版本和预算快照。
- 执行检索上下文压缩、资料整理、三个 writer、确定性 JSON 归一化、语义校验和模型兜底修复。
- 记录 provider/model、prompt/completion/total token、耗时、repair 原因和成本未知状态。
- 只生成结构化 artifact，不把调试日志、页面视觉上传图、内部 URI 或资源卡写入讲义正文。
- 资源阶段结束后按引用计数/任务状态清理视觉副本；取消、失败、超时和成功都必须进入同一 finally 清理路径。

### 14.2 Java/平台侧仍需保留的边界

- MySQL 是 workflow/stage 状态、幂等键、revision、产物元数据和权限的唯一真相；RabbitMQ 消息不是最终状态。
- ACK 必须晚于 artifact、usage、状态和成本账本写入；重复消息按幂等键返回已持久化结果。
- MCP、租户身份、教师权限、PDF 发布记录和资源生命周期状态仍由平台契约保护。
- 迁移过渡期的 `PythonHandoutClient` 只能作为兼容适配器；Python worker 稳定后，应删除 Java 中重复的 AI 编排和模型调用路径。

### 14.3 JSON 确定性修复顺序

```text
原始响应
  -> 去 BOM、代码围栏和外围说明
  -> 使用 JSON parser 提取唯一顶层 object/array
  -> 按 schema 归一化字符串、数组和可选字段
  -> 校验题目数、顺序、题干、知识点、提示/解答非空及证据绑定
  -> 成功则继续导出
  -> 失败只携带错误字段和最小上下文调用一次模型兜底
  -> 再次完整校验；仍失败则 FAILED，不生成空讲义
```

代码注释应解释清楚“为什么先代码修复、为什么共享版本和清理状态、为什么 ACK 必须在落库之后”，并写在归一化、租约、幂等、资源清理和导出门禁等关键逻辑旁；普通赋值不写空洞注释。任何迁移实现都必须使用本文件第 4 节的四道题作为完整 fixture，不能用只有一题或字符串 `lecture` 的缩减数据掩盖解析问题。

### 14.4 迁移验收门槛

- 同一四题输入在 Java 旧链路和 Python 新链路产生相同题目数量、顺序、权限过滤结果和三种产物契约。
- 真实 provider 至少完成一次全流程；测试不能用 fake provider、模拟 PDF 或伪造 token 代替真实验收。
- 重复投递、Worker 崩溃、租约过期、取消任务和超预算必须各有真实集成测试。
- 视觉上传页面图必须在资源阶段结束后不可被 artifact 引用；仅题目绑定的几何图或图表可以进入正文。
- 验收输出必须保存 workflow、trace、artifact、PDF、逐页 PNG、页数、尺寸、文本检查结果和 SHA-256，且全部写入日期/类型对应的 `docs/todo/finished/` 记录。
