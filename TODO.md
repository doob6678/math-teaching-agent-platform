# TODO — 已知问题与修复清单

> **必读。** 下面每条都经过源码核对，附精准位置和影响评估。按优先级排序。

---

## P0 — 严重（影响核心功能正确性）

### B1: 学生讲题"二次函数"检索过滤误杀"抛物线"（已修复 2026-08-03）

- **位置**: `StudentExplanationService.matchesConcreteTopic()` 约第 805 行
- **源码**: `!text.contains("抛物线")` 在二次函数过滤分支里
- **问题**: 抛物线 `y=ax²+bx+c` 就是二次函数的几何图像。排除"抛物线"意味着检索"二次函数"时，教材里 80% 提到"抛物线"的段落全被丢弃
- **后果**: 学生问"二次函数顶点坐标"时教材召回率大幅降低
- **修复**: 已从二次函数过滤条件中移除 `&& !text.contains("抛物线")`。

### B2: 路 A 多 Agent 写作 — imageDescription 空 → 图像编排断链（已修复 2026-08-03）

- **位置**: 
  - `TeacherResourceVisualEvidenceService.materialize()` 第 43 行：`new MaterializedImageEvidence(path.get(), "")`
  - `TeachingWorkflowExecutionSupport.materializeTeacherImage()` 第 1014 行：`.map(path -> new MaterializedImageEvidence(path, ""))`
- **问题**: `imageDescription` 字段永远为空字符串，vision adapter 未接入。Writer 只看得到 `TEACHER_IMAGE: /api/...` URI，不知道图长什么样
- **后果**: Writer 生成"如图，抛物线开口..."时，和渲染层插的图之间没有语义对应。图像编排子功能实际不可用
- **修复**: 已在授权图片证据实例化时传入同一资料块已解析的相邻文本，并保留原始像素给多模态模型；不从文件名、路径或模型臆测生成描述。

---

## P1 — 中等（影响可靠性/运维可读性）

### B3: Token 计数器缺失时上下文安全检查被静默绕过（已修复 2026-08-03）

- **位置**:
  - `StudentExplanationTokenCounter.count()` 第 38-39 行：worker 未配置时返回 `TokenCount.unavailable()`
  - `StudentExplanationAiCardService` 依赖 `tokenCounter.count()` 做 `conversationContextMaxTokens` 上限控制
- **问题**: embedding worker 宕机时，所有 token 检查返回 `unavailable`，上下文上限检查被静默绕过
- **后果**: 长对话可能撑爆模型上下文窗口，出现截断或幻觉
- **修复**: tokenizer 不可用时按 4096 token 等价字符上限截断上下文，并记录告警日志。

### B4: 知识图谱匹配只有字符重叠，无语义降级（已修复 2026-08-03）

- **位置**: `StudentExplanationService.knowledgeScore()` 第 777-793 行
- **问题**: 仅靠字符重叠会把相近但不同的概念排在一起；向量服务异常时还必须保留可解释的词面结果
- **后果**: 知识点匹配不准确，或向量基础设施故障时无法区分“未命中”和“降级匹配”
- **修复**: 已接入 `VectorIndexService.semanticSimilarity()`，以配置阈值 `knowledge-graph-semantic-min-score` 纳入语义分数；向量服务异常、返回数量不一致或主干为空时保留安全词面路径并记录 tenant/subject/query 维度告警。

### B5: Ingestion 成功也标 PARTIALLY_FAILED（已修复 2026-08-03）

- **位置**: `IngestionBatchRunner.execute()` 第 78-80 行
- **源码**: 所有 run 结束时均写 `PARTIALLY_FAILED` + `VERIFICATION_FAILED`，注释说"视觉审核还没做"
- **问题**: 运维看到 run 全是 PARTIALLY_FAILED 会误以为批次出错，实际上只是 pipeline 没跑完
- **修复**: 已新增 `PARSED_AWAITING_REVIEW`，解析完成后保留 `NOT_STARTED` 审核状态，不再误标失败。

---

## P2 — 低（一致性/健壮性缺陷）

### B6: ReAct 决策只用第一个 provider，无降级（已修复 2026-08-03）

- **位置**: `StudentExplanationAiCardService.nextReactDecision()` 第 85 行
- **源码**: `AiProviderCatalog.Provider provider = providers.getFirst();`
- **问题**: ReAct 决策和 card 生成永远用第一个 enabled provider，无 fallback 切换到第二个
- **对比**: `AgentRunExecutionService` 有完整 provider 降级循环
- **修复**: ReAct 决策现按已启用 provider 顺序进行有界回退并记录失败告警。

### B7: searchTeacherResources 吞异常 vs searchTextbooks 抛异常（已修复 2026-08-03）

- **位置**: `StudentExplanationService` 第 649 行 vs 第 576 行
- **修复**: 两条检索链路统一为记录结构化告警、阶段标记 `degraded`、返回空证据继续讲解。

### B8: StudentMemoryRagService 静默吞异常（已修复 2026-08-03）

- **位置**: `StudentMemoryRagService` retrieve() 第 55 行 / index() 第 71 行
- **修复**: 读写失败保留业务降级，并以 tenant/student/explanation 维度记录告警日志。

### B9: FeishuResourceBindingService 无重试无错误处理（已修复 2026-08-03）

- **位置**: `FeishuResourceBindingService`
- **修复**: 数据库瞬态失败会有限重试一次、记录结构化告警；最终失败返回明确的 `FEISHU_RESOURCE_BINDING_FAILED`。

---

## 其他已知技术债（非 bug，但需关注）

- **TeachingWorkflowService** 1331 行上帝类，需拆分（已发生过误删事故，严禁正则批量操作）
- **两条讲义链路并存**（MultiAgentWritingService vs TeachingWorkflowService），尚未收敛
- **多 Agent 写作的六个扩展阶段暂未接入执行链**：`template_selection`、`outline_planning`、`source_review`、`student_safety_review`、`layout_review`、`merge_coordinator` 不会被当前工作流调用；提示词与旧 artifact 的解析分支仅作为兼容契约保留。当前有意维持“资源整理 + 三个并行可发布版本”的四阶段链路，以控制 token、延迟和调用成本。若要扩展，必须先确认产品、成本和发布契约，不能仅依据 TODO 改变拓扑。

---

最后更新: 2026-08-03 14:55 CST（Asia/Shanghai） | 来源: 全线源码审查、定向测试、默认模型复核与多 Agent 拓扑复核
