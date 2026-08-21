# 检索架构违规审查报告

生成时间：2026-08-18  
审查范围：Java 后端检索链路、Python AI Agent MCP 工具

## 核心问题

**当前架构违反了 "AI Agent 自主检索" 原则**：Java 后端直接将用户输入转换为检索 query 并执行向量检索，而不是让 Python AI Agent 根据教学目标自主生成检索关键词。

## 架构要求（已写入 README.md 和 AGENTS.md）

### 正确流程

1. **Java 负责**：接收用户教学目标、校验权限、创建持久化任务、签发 `runId`、提供初始 `evidenceRefs`（如有）
2. **Python AI 负责**：
   - 在 `plan_writer` 节点根据教学目标自主生成检索关键词
   - 通过 `teacher_resource_curation` 节点调用 `handout-teacher-resource-search` 工具
   - 通过 `handout-document-read`/`handout-document-search` 工具深入阅读已授权文档
3. **Java MCP 工具边界**：校验 `runId`、执行向量检索、返回不透明 `evidenceRef`

### 错误模式（禁止）

- ❌ Java 直接调用 `retrievalService.search(userInput)`
- ❌ 在运行时扫描文件系统搜索资料
- ❌ 前端直接传入文件路径或 URL

## 违规代码位置

### 1. TeachingWorkflowExecutionSupport.java（主要违规）

**文件**：`backend-java/src/main/java/com/doob/mathagent/teaching/service/TeachingWorkflowExecutionSupport.java`

**违规函数**：
- `retrieveTextbookEvidence()` - 第 769 行
- `retrieveQuestionBankEvidence()` - 第 825 行
- `retrieveTeacherResourceEvidence()` - 第 928 行

**违规模式**：
```java
// 第 777-808 行：直接将用户输入转换为检索 query
List<String> queries = alignedQueries(request);
String primaryTopic = primaryTopicKeyword(request);
// ...
TextbookSearchResponse retrieval = retrievalService.search(
    processedBooksRoot,
    new TextbookSearchRequest(query, request.evidenceLimit()),
    retrievalContext);
```

这些方法直接从 `TeachingTaskRequest` 提取 `learningGoal`、`questionText` 等用户输入，转换为检索 query，并立即执行向量检索。

**影响**：教材检索、题库检索、教师资料检索全部违反架构要求。

### 2. KnowledgeRetrievalAgentService.java（次要违规）

**文件**：`backend-java/src/main/java/com/doob/mathagent/agent/service/KnowledgeRetrievalAgentService.java`

**违规代码**：
```java
// 第 41-47 行
public KnowledgeEvidencePackResponse retrieve(KnowledgeRetrievalAgentRequest request, RequestSubject subject) {
    KnowledgeRetrievalAgentRequest normalized = request.normalize();
    RequestSubject owner = subject.normalize();
    var response = retrievalService.search(textbookProperties.processedBooksRoot(),
            new TextbookSearchRequest(normalized.query(), normalized.limit()),
            // ...
```

**说明**：这个服务直接接收外部的 `query` 并执行检索。虽然它可能被设计为通用检索服务，但如果被前端直接调用（通过 `KnowledgeRetrievalAgentController`），就违反了架构要求。

### 3. 正确实现：AgentToolBrokerController.java（符合架构）

**文件**：`backend-java/src/main/java/com/doob/mathagent/agent/controller/AgentToolBrokerController.java`

**正确模式**：
```java
// 第 186-208 行：handoutTeacherResourceSearch
@PostMapping("/handout-teacher-resource-search")
public Map<String, Object> handoutTeacherResourceSearch(
        @RequestHeader("X-Agent-Worker-Key") String workerKey,
        @Valid @RequestBody HandoutTeacherResourceSearchRequest request) {
    authorize(workerKey);
    RequestSubject subject = subjectForHandoutRun(request.runId());
    String query = request.query().strip();  // ← query 来自 AI，不是用户
    // ...
    TeacherResourceBlockSearchResponse response = resourceSearchService.search(
            subject.tenantId(), subject.subjectType(), subject.subjectId(), query, request.limit(),
            "/internal/agent-tools/v1/handout-teacher-resource-search");
```

**关键特征**：
- 需要 worker 密钥验证
- 通过 `runId` 追溯授权
- `query` 参数来自 Python AI Agent，不是直接来自用户输入
- 返回不透明 `evidenceRef`

## Python AI 实现状态

### 已实现（符合架构）

**文件**：`ai-worker-python/app/handout_runtime.py`

1. **resource_curation 节点**（第 1050 行）：接收 Java 签发的初始 `evidenceRefs`
2. **plan_writer 节点**（第 1112 行）：AI 根据教学目标生成教学计划
3. **teacher_resource_curation 节点**（第 1078 行）：
   ```python
   queries = list(dict.fromkeys(
       query.strip() for query in (plan.teacher_resource_queries if plan else [])
       if isinstance(query, str) and query.strip()))[:4]
   # ...
   response = self._java_broker_request("handout-teacher-resource-search", payload,
                                        deadline_epoch_ms=request.deadline_epoch_ms)
   ```
   - AI 在 `plan_writer` 中决定 `teacher_resource_queries`
   - 然后通过 MCP 工具调用 Java 检索

**结论**：Python AI 的讲义生成链路（handout_runtime.py）已经正确实现了 AI 自主检索。

## 修复建议

### 优先级 P0（必须修复）

1. **TeachingWorkflowExecutionSupport.java**
   - 将 `retrieveTextbookEvidence`、`retrieveQuestionBankEvidence`、`retrieveTeacherResourceEvidence` 重构为仅接收 **AI 已生成的 query 列表**
   - 不再从 `TeachingTaskRequest` 中提取用户输入并转换为 query
   - 用户输入（`learningGoal`、`questionText`）应该只传递给 Python AI，由 AI 决定检索策略

2. **教学任务创建流程**
   - Java 接收用户输入后，创建任务并签发 `runId`
   - 将用户输入作为 `writingGoal` 传递给 Python handout runtime
   - Python AI 在 `plan_writer` 节点自行生成检索 query
   - Python AI 通过 MCP 工具回调 Java 执行检索

### 优先级 P1（需要评估）

3. **KnowledgeRetrievalAgentService**
   - 评估当前调用方：如果只被 Python AI 或内部服务调用，可保留
   - 如果被前端直接调用，需要重构为 MCP 工具模式

4. **添加架构测试**
   - 编写测试验证：前端请求不能直接触发向量检索
   - 验证所有检索必须通过已签发的 `runId` 和 worker 密钥

### 优先级 P2（文档与审计）

5. **代码注释**
   - 在所有 `retrievalService.search()` 调用处添加注释，说明 query 来源
   - 标记符合/不符合架构要求的调用

6. **审计日志**
   - 记录每次检索的触发源：是 AI 生成的 query 还是用户直接输入
   - 在 `handout_document_inspection` 日志中已包含 `runId` 和 `operation`

## 当前状态总结

### ✅ 已正确实现
- Python handout runtime 的完整 AI 自主检索链路
- `AgentToolBrokerController` 的 MCP 工具接口
- 不透明 `evidenceRef`/`documentRef` 机制
- Worker 密钥验证和 `runId` 授权

### ❌ 需要修复
- Java 后端的教学任务执行链路仍在直接将用户输入转换为检索 query
- 这违反了 "AI Agent 自主检索" 的架构原则

### ⚠️ 过渡方案
在完全修复前，可以：
1. 保持现有代码用于非讲义场景（如教材检索页面）
2. 对讲义生成任务，强制使用 Python handout runtime
3. 添加明确的代码路径标记，区分 "直接检索"（待废弃）和 "AI 检索"（推荐）

## 验证方法

修复后，运行以下测试验证架构合规性：

1. **端到端测试**：
   ```powershell
   # 创建讲义任务
   POST /api/teaching/tasks
   {
     "learningGoal": "讲解二次函数顶点式",
     "questionText": "【题目1】求函数 f(x)=x²+4x+3 的顶点坐标"
   }
   
   # 验证：
   # - Java 不应直接执行 retrievalService.search()
   # - 应调用 Python handout runtime
   # - Python 日志显示 plan_writer 生成了 teacher_resource_queries
   # - Python 调用 /internal/agent-tools/v1/handout-teacher-resource-search
   # - Java 审计日志记录 handout_document_inspection
   ```

2. **日志验证**：
   ```
   # Python 日志应包含：
   {"event": "plan_ready", "phase": "PLAN_DRAFTED"}
   {"event": "node_completed", "node": "teacher_resource_curation", "queryCount": 3}
   
   # Java 日志应包含：
   handout_document_inspection runId=task_xxx operation=model-teacher-search
   ```

3. **禁止的模式**：
   ```
   # ❌ 不应出现：
   - Java 日志中 teaching-workflow 直接调用 TextbookRetrievalService.search()
   - 用户输入直接作为 query 参数
   ```

## 后续行动

1. **立即行动**：在 README.md 和 AGENTS.md 中明确写入架构要求 ✅ 已完成
2. **本周修复**：重构 TeachingWorkflowExecutionSupport.java
3. **下周验收**：运行完整讲义生成验收测试，验证 AI 自主检索
4. **文档更新**：在架构文档中补充检索链路时序图

---

**报告生成者**：ZCode AI Agent  
**审查依据**：README.md "不可违反的讲义架构" 第 5 条、AGENTS.md "检索链路架构（强制执行）"
