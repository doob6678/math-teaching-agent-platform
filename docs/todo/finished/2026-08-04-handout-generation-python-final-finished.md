# 2026-08-04 讲义生成审计、Python 迁移与性能验收完成记录

> 类型：讲义生成 / Python LangGraph / RabbitMQ / 性能 / Token / 成本 / 进度 / 完整测试数据
>
> 状态：本轮修复、真实验收和迁移记录已完成；文末明确保留的生产化缺口不能视为已完成。

## 1. 最终判断

系统已经从“同步调用模型后直接排版”的 Demo 形态，提升为具备真实模型、多阶段编排、RabbitMQ 异步 Worker、MySQL 状态与 usage 账本、Redis 并发控制、SQLite checkpoint、确定性 JSON/Markdown 门禁、真实 XeLaTeX/PDF 导出和布局审计的工程实现。

当前仍不能认定为一流水平的完整生产系统，主要缺口是：

- 独立的数学答案一致性、内容审校和学生安全审校还没有形成每次发布必经的独立闭环。
- 已有 RabbitMQ 调度、ACK、租约、重试、DLQ 和恢复链路，但 queue wait、lease wait、render/export、ACK 延迟、重复投递率和 P50/P95/P99 还没有完整落到账本与监控。
- Token 已有真实 Provider usage；未配置 Provider 价格时成本必须显示未知，不能把 Token 估算或 `0` 伪装成货币成本。
- 临时导出图片和最终 PDF 嵌入图片已验证回收/不进入产物；持久化视觉上传资产的引用计数、TTL、取消和失败任务全生命周期仍需补充真实集成验收。

## 2. 本轮修复的问题

修复前的 16:10 讲义曾出现单题或局部内容、完整四题丢失、题目顺序不稳定、资源卡/证据卡/上传页面图混入正文、横线和填空线、`<wait>`、内部日志、空白重复页和 CJK TeX 文本错误。根因不是单一模型质量问题，而是输入批次边界、模型输出协议、确定性门禁、异步状态和导出审计没有统一契约。

本轮已完成：

- 16:10 版本保留完整四道题、知识主线、方法检查点和总结；采用稳定教学单元分页，不强行压成单页。
- Python LangGraph 固定为资料整理后并行执行 `teacher_writer`、`student_writer`、`lecture_writer`，汇总后执行结构/语义门禁和导出。
- JSON 优先由代码去 BOM、围栏、外围说明并解析/归一化；代码先清除 `<wait>`、Markdown 横线和讲义不应出现的填空下划线。只有结构或语义门禁仍失败时，才允许一次最小上下文 repair；repair 后重新完整校验，仍失败则 FAILED。
- 16:10 禁止资源卡、证据卡、`TEACHER_IMAGE`、资料依据、答案泄漏、内部日志和视觉上传页面图；临时导出复制文件在 finally 路径回收。
- Python 通过 Java `handout-context` 只获得已经过身份、租户和资源权限过滤的证据，不接收请求体中的租户身份、资源路径或原始资产；Provider 凭证只来自 Worker 环境变量。
- Java 异步入口只投递一个 `PythonHandoutAgent/python_handout` 任务。Worker 在 MySQL lease 下调用一次 Python Graph，结果、usage 和状态持久化成功后才 ACK；重复投递先查幂等状态，checkpoint resume 跳过已成功节点。
- Python usage 写入失败在生产配置 `MATH_AGENT_USAGE_REQUIRED=true` 时使图失败，避免“产物成功但成本账本丢失后仍 ACK”。
- 修复 XeLaTeX 中 CJK 数学文本的反斜杠处理，避免 PDF 输出 `text 结构识别` 一类错误文本。

## 3. 完整测试输入

本轮契约测试、checkpoint resume 测试和最新真实 workflow 使用同一组四题输入。不能用单题、只有 `lecture` 字符串或只断言题目数量的缩减 fixture 替代：

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

四题的语义要求如下：

1. 根式分式函数 `f(x)=sqrt(x+1)/(x-2)` 的定义域。
2. `g(x)=x+1/x` 且 `x>0` 时的最小值，必须保留正性和等号成立条件。
3. `h(x)=x^2-2ax+1` 在 `[0,2]` 上最小值为 `-3`，求实数 `a`，必须保留对称轴位置分类。
4. 正方体 `ABCD-A_1B_1C_1D_1` 中直线 `AC_1` 与平面 `A_1BD` 的线面角，必须保留空间向量/法向量建模关系。

测试还覆盖了四个 question card 按 1→2→3→4 投影、resource card 过滤、列表字段投影、缺题拒绝、截止时间拒绝、部分 checkpoint resume 和完整 writer checkpoint 复用。

## 4. 迁移边界与工作流

### 4.1 Python 负责

- LangGraph 节点编排、证据上下文压缩、三个 writer、结构化解析、确定性清洗、语义门禁和有限 repair。
- 调用真实 Provider，并记录 provider、model、prompt/completion/total token、调用次数、耗时和成本未知状态。
- 写入受控的 `ai_usage_event` usage 账本，不能写 Java 的身份、业务 workflow 或资产权限数据。
- 使用 SQLite checkpoint 保存节点边界，使进程重启时不重复调用已成功的模型节点。

### 4.2 Java 仍负责

- 租户/用户身份、权限、MCP 工具授权、证据可见性、业务 workflow、MySQL 状态、产物发布和资源生命周期。
- `handout-context` 作为 Python 唯一的证据边界；Python 不直接访问业务数据库、Milvus、教师资产原图或请求中的权限字段。
- RabbitMQ 任务的 lease、ACK、有限重试、DLQ、幂等和 Worker 崩溃恢复。
- 真实 PDF 发布、文件元数据和导出阶段的最终权限门禁。

### 4.3 RabbitMQ 为什么使用，以及它解决不了什么

本系统应该使用消息队列。一个 `python_handout` 消息代表一次可恢复的异步工作单元，负责：

- 解耦 HTTP 请求与长时间模型/渲染任务。
- 削峰、并发隔离、优先级、prefetch、Provider QPS/TPM 限制和租户公平性。
- 对暂时性 Provider/网络/Worker 错误做延迟重投递，对不可恢复错误进入 DLQ。
- Worker 崩溃时依靠未 ACK 消息和 MySQL lease 恢复。

RabbitMQ 不是 workflow 真相，也不保存完整讲义、Token、成本账本或质量审计结果；它不能替代 MySQL 的幂等、revision/CAS、fencing，也不能解决上下文过大、模型输出过长和 Provider 尾延迟。正确提交顺序是：取消息 → 校验 lease/fencing → 检查 stage 幂等状态 → 检索/模型 → 代码清洗和门禁 → 写入 artifact、usage、成本和状态 → 原子更新快照 → 成功后 ACK。

## 5. 进度设计

当前进度由 MySQL workflow 快照和 SSE 变化推送承载，阶段成功/失败/resume 可持久化，状态指纹用于去重。真实 DAG 为：

```text
queued
  -> lease_acquired
  -> resource_curation
  -> teacher_writer / student_writer / lecture_writer 并行
  -> json_parse / json_repair（仅失败时）
  -> content_gate
  -> render_queue_wait -> xelatex_compile -> pdf_render -> layout_audit
  -> export -> persisted -> completed
```

还必须把 `queue_wait`、`lease_wait`、`retry_wait`、`render_queue_wait`、每个节点 attempt、revision、错误分类、Token、成本和 start/end 时间作为可查询事件。当前能看到离散阶段状态，但缺少可靠的全链路分段和 P50/P95/P99，不能把“请求已发送”显示成“阶段已完成”。

## 6. 真实 Token、时延和成本

最新真实 workflow：`bf9f461c-5ebf-43fe-bcb6-3f10d1d0ad4d`；Provider 为 `openai`，model 为 `gpt-5.6-terra`；四阶段全部 COMPLETED，JSON repair 次数为 0。

| 阶段 | Prompt Token | Completion Token | Total Token | Model 调用耗时 |
|---|---:|---:|---:|---:|
| `resource_curation` | 8,068 | 925 | 8,993 | 21,675 ms |
| `student_writer` | 6,775 | 2,901 | 9,676 | 53,268 ms |
| `lecture_writer` | 6,869 | 2,533 | 9,402 | 47,382 ms |
| `teacher_writer` | 9,149 | 5,972 | 15,121 | 109,610 ms |
| **合计** | **30,861** | **12,331** | **43,192** | **231,935 ms** |

三个 writer 并行，因此 231,935 ms 不能当作端到端等待时间；端到端还包括队列、lease、MySQL、汇总、XeLaTeX、PNG、布局审计和导出。当前已能真实记录 Token，但本次 Trace 的 `actualCost=-1.0`、`costKnown=false`，因为环境未配置与 Provider/model/价格版本对应的真实价格目录。没有价格配置时不能写人民币或美元金额；历史 `estimatedCost` 也只能当估算，不能当作已结算费用。

生产成本闭环应在模型请求前预留 prompt + 最大 completion Token，并按 workflow/stage/tenant 做硬预算；本轮 Python 已增加 `MATH_AGENT_HANDOUT_MAX_TOTAL_TOKENS=56000`、`MATH_AGENT_HANDOUT_MAX_PROVIDER_CALLS=8` 的预留检查，repair/retry 与并行 writer 共享预算。达到上线要求后还应持久化缓存 Token、价格版本、币种、输入成本、输出成本、重试原因和账单状态。

## 7. PDF 真实验收

证据目录：`output/mcp-acceptance/mcp-luna-handout-20260804T173833Z/`。

修复后通过 `pypdf` 读取和哈希核验的最终产物：

| 产物 | 页数 | 页面尺寸 | 文本字符数 | 字节数 | SHA-256 | 图片嵌入 |
|---|---:|---|---:|---:|---|---:|
| 教师版 | 6 | A4 (`595.28 × 841.89`) | 6,060 | 135,553 | `50de70b3d2eb3cbf554bd9464bed3e50b5f3a159961aaf4e38832f1dea8ca013` | 0 |
| 学生版 | 4 | A4 (`595.28 × 841.89`) | 3,050 | 118,898 | `1003c67e2f9320f06be86f56d9ba711fd7b3c4ab00c6706fda5adf08835b5567` | 0 |
| 16:10 课堂投影版 | 12 | `921.6 × 576` | 2,358 | 101,439 | `0fba5bc80a4bbd514b43cad1832203f00e89e6202f9f88c5d1e63871df92b6f2` | 0 |

三种产物均保留四题关键标记：`定义域`、`最小值`、`实数 a`、`正方体`、`AC`、`平面`。16:10 最终文本中不存在 `<wait>`、横线 `---`、填空下划线、`TEACHER_IMAGE`、`资料依据`、`资源卡`、`证据卡`、`内部日志`或页面视觉上传图。视觉复核的知识地图、先备知识、四题方法页和总结页均为题目/知识内容加真实纯白区域，没有横线和填空线。

旧 `acceptance-summary.json` 曾把渲染 PNG 统计成 1、1、3 页；这是验收脚本计数错误，不能覆盖实际 PDF 证据。真实 PDF 页数和逐页 PNG 文件计数为教师 6、学生 4、16:10 12。

## 8. 测试与结果

已真实通过：

- `mvn -q -DskipTests compile`：exit 0。
- 健康 `math-agent-rag-ai-worker-1` 容器加载本轮 Python 源码后执行 `test_handout_runtime.py`：`Ran 8 tests in 0.835s; OK`。
- `python3 -m py_compile ai-worker-python/app/handout_runtime.py ai-worker-python/app/usage.py ai-worker-python/tests/test_handout_runtime.py`：通过。
- 最新真实 MCP workflow：四阶段成功、真实 Provider、0 次 JSON repair，usage 为 43,192 tokens。
- 三份 PDF 的文本、页数、页面尺寸、逐页 PNG 和图片嵌入检查通过。

### 8.1 本轮 Python Graph 真实调用

在已连接 Java broker、MySQL usage 账本和真实 Provider 的 Docker Worker 中，用本文件第 3 节的完整四题输入再次执行 Python Graph，结果为：

```text
status=COMPLETED
validation.valid=true
validation.repaired=false
validation.errors=[]
documents=[lecture_writer, student_writer, teacher_writer]
resource_curation: SUCCESS, elapsed=1,560 ms
student_writer: SUCCESS, provider=openai, model=gpt-5.6-luna, prompt=8,594, completion=765, total=9,359, elapsed=20,518 ms
teacher_writer: SUCCESS, provider=openai, model=gpt-5.6-luna, prompt=8,591, completion=3,041, total=11,632, elapsed=58,817 ms
lecture_writer: SUCCESS, provider=openai, model=gpt-5.6-luna, prompt=8,591, completion=3,575, total=12,166, elapsed=68,630 ms
structured_validation: SUCCESS
total prompt=25,776, completion=7,381, total=33,157
JSON repair calls=0
estimatedCost=-1.0, costKnown=false
```

这次真实重投递复用了已有 workflow id，也验证了 `ai_usage_event` 的数据库唯一键幂等写入：已存在的同一 `run_id + provider + attempt` 事件不会重复计费，非重复 MySQL 错误仍会因 `MATH_AGENT_USAGE_REQUIRED=true` 使图失败。

### 8.2 真实启动中发现并修复的阻断

- 当前源码 `ai-worker-python/app/server.py` 的 FastAPI import 在 `try` 块中曾少一级缩进，Windows Python 启动直接报 `IndentationError`；已修复并通过 `py_compile`。
- 并发 Docker BuildKit 构建曾以旧源码快照打包 `server.py`，导致新镜像存在 `handout_runtime.py` 但 OpenAPI 没有 handout 路由；验收期间发现后，重新以当前源码层启动验证，OpenAPI 已包含 `/v1/handout-runs/sync`。
- 宿主机虚拟环境缺少 `pymysql`，生产 usage 必须落库时正确返回失败；这没有被记录为模型失败，也没有绕过 `MATH_AGENT_USAGE_REQUIRED`。

测试命令：

```text
docker cp ai-worker-python/app/handout_runtime.py math-agent-rag-ai-worker-1:/tmp/current-app/app/handout_runtime.py
docker cp ai-worker-python/app/usage.py math-agent-rag-ai-worker-1:/tmp/current-app/app/usage.py
docker cp ai-worker-python/tests/test_handout_runtime.py math-agent-rag-ai-worker-1:/tmp/test_handout_runtime.py
docker exec -e PYTHONPATH=/tmp/current-app:/app math-agent-rag-ai-worker-1 sh -lc 'python3 /tmp/test_handout_runtime.py'
```

不能宣称全量测试通过：Python 主机环境缺少完整项目依赖，Python 全量 discover 曾有 4 个依赖导入错误和 1 个既有 embedding 错误；历史 Java 全量为 756 tests、52 failures、15 errors、8 skipped。定向 Java 测试和编译通过不等价于全量通过，后续必须按 Windows 编码、XeLaTeX/字体环境、旧检索排序断言和异步时序耦合分类治理。

## 9. 必须继续治理的生产缺口

- 给 workflow/stage 增加 revision/CAS 或等价原子合并，并用两个以上 Worker 做并发更新集成测试。
- 增加可靠 lease 续租和 fencing token，校准 Provider timeout、重试总时长和租约时长。
- 记录 queue wait、lease wait、retry、render、export、total、ACK latency、积压量、重复投递率及 P50/P95/P99。
- 接入真实 Provider 价格账本，按价格版本、币种和阶段结算；预算超限必须在请求前阻断。
- 为资料整理和三个 writer 分别设置输入/输出/repair/retry Token 预算，并用长资料和复杂几何题做压力测试。
- 为持久化视觉资产增加引用关系、TTL、取消/失败任务回收和定时清理的真实生命周期测试。
- 恢复或明确裁剪独立数学审校、内容审校、学生安全审校和答案一致性审校闭环。
- 统一主教学 DAG 与 Python 多 Agent 链路的 workflow、stage、事件、Token、成本和质量门禁模型。

## 10. 关键源码和证据

- `ai-worker-python/app/handout_runtime.py`
- `ai-worker-python/app/usage.py`
- `ai-worker-python/tests/test_handout_runtime.py`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingService.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/worker/AgentWorkerTaskConsumer.java`
- `backend-java/src/main/java/com/doob/mathagent/agent/service/MultiAgentWritingArtifactExportService.java`
- `docs/python-langgraph-handout-migration.md`
- `output/mcp-acceptance/mcp-luna-handout-20260804T173833Z/trace.json`
- `output/mcp-acceptance/mcp-luna-handout-20260804T173833Z/artifact.json`
- `output/mcp-acceptance/mcp-luna-handout-20260804T173833Z/acceptance-summary.json`
