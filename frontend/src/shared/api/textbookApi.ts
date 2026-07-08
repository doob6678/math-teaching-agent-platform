/**
 * 教材目录摘要。字段与后端 `TextbookResourceSummary` 对齐，用于资料搜索页顶部资源概览。
 */
export interface TextbookSummary {
  /** 后端实际读取的 processed_books 根目录，帮助排查当前连接的是哪批教材资源。 */
  processedBooksRoot?: string;
  /** 已接入教材数量。 */
  bookCount: number;
  /** 所有教材 chunk 总数，反映可检索文本块规模。 */
  totalChunkCount: number;
  /** 所有教材 PDF 页总数，反映页面证据覆盖规模。 */
  totalPageCount: number;
  /** 教材明细列表，当前前端主要用于后续扩展筛选。 */
  books: TextbookCatalogItem[];
}

/**
 * 单本教材的 catalog 元数据。字段名保留后端 JSON snake_case，避免前端误映射。
 */
export interface TextbookCatalogItem {
  /** 教材唯一标识，如 renjiao_bbixiu1math。 */
  doc_id: string;
  /** 教材显示名称。 */
  book_name: string;
  /** 教材册别，如“必修 第一册”。 */
  volume: string;
  /** 处理后单本教材目录。 */
  book_root: string;
  /** 单本教材 manifest 文件路径。 */
  manifest: string;
  /** 当前教材 chunk 数量。 */
  chunk_count: number;
  /** 当前教材页数。 */
  page_count: number;
  /** 旧 OCR/AI 处理链路是否完成的标记。 */
  ai_ok: boolean;
}

/**
 * 单条教材检索证据。字段来自后端 `TextbookSearchHit`，用于展示引用和审计线索。
 */
export interface TextbookSearchHit {
  /** 命中的 chunk 唯一 ID，可用于后续证据审计和反馈。 */
  chunkId: string;
  /** BM25/重排后的分数，越高表示当前策略下越相关。 */
  score: number;
  /** 命中使用的具体召回策略，如 local_bm25。 */
  retrievalStrategy: string;
  /** 教材唯一标识。 */
  docId: string;
  /** 教材显示名称。 */
  bookName: string;
  /** 教材册别。 */
  volume: string;
  /** 章节路径，从章到节逐级展示。 */
  chapterPath: string[];
  /** PDF 页码。 */
  pageNo: number;
  /** 教材印刷页码，OCR 未识别时可能是“未识别”。 */
  printedPageNo: string;
  /** 命中 chunk 所在小节标题。 */
  sectionTitle: string;
  /** 后端截断后的证据片段。 */
  textSnippet: string;
  /** 命中页抽取的公式文本，可能为空。 */
  formulaText: string;
  /** 页面图片相对路径，如 pages/p135.png。 */
  sourcePageImage: string;
  /** 页面质量标签，用于识别正文页、封面、目录、数字附录等。 */
  pageQualityLabel: string;
}

/**
 * 教材检索响应。query/limit 会原样回显，queryId 用于和后端审计日志对齐。
 */
export interface TextbookSearchResponse {
  /** 本次检索审计 ID，对应后端 retrieval_query_log.query_id，可用于排查和追踪。 */
  queryId: string;
  /** 实际检索 query。 */
  query: string;
  /** 后端采用的结果条数上限。 */
  limit: number;
  /** 检索总策略，如 local_bm25_first。 */
  retrievalStrategy: string;
  /** 返回 hit 数量。 */
  total: number;
  /** 命中的教材证据列表。 */
  hits: TextbookSearchHit[];
}

/**
 * 检索请求上下文审计字段。字段来自后端 `RetrievalRequestContext`，用于排查调用来源。
 */
export interface RetrievalRequestContextAudit {
  /** 租户标识；当前单租户阶段通常为 default。 */
  tenantId?: string;
  /** 主体类型，如 teacher、student、guest、admin、api_key。 */
  subjectType?: string;
  /** 主体 ID；未接入登录态时可能为空。 */
  subjectId?: string;
  /** 客户端 IP，用于风控和异常排查。 */
  ip?: string;
  /** 设备 ID，用于识别同设备异常检索。 */
  deviceId?: string;
  /** 浏览器或调用方 User-Agent。 */
  userAgent?: string;
  /** 实际触发检索的后端 endpoint。 */
  endpoint?: string;
}

/**
 * 单条检索命中审计详情。字段来自后端 `RetrievalAuditHit`。
 */
export interface RetrievalAuditHit {
  /** 当前查询内的排序名次，从 1 开始。 */
  rankNo: number;
  /** 命中的教材 chunk 唯一 ID。 */
  chunkId: string;
  /** 教材文档 ID。 */
  docId: string;
  /** 教材显示名称。 */
  bookName: string;
  /** PDF 页码。 */
  pageNo: number;
  /** 教材印刷页码。 */
  printedPageNo: string;
  /** 命中分数。 */
  score: number;
  /** 产生该命中的具体召回策略。 */
  retrievalStrategy: string;
  /** 页面质量标签。 */
  pageQualityLabel: string;
  /** 页图相对路径。 */
  sourcePageImage: string;
  /** 教材册别。 */
  volume: string;
  /** 章节路径。 */
  chapterPath: string[];
  /** 命中小节标题。 */
  sectionTitle: string;
  /** 审计中保留的正文片段。 */
  textSnippet: string;
  /** 审计中保留的公式文本。 */
  formulaText: string;
}

/**
 * 检索审计详情响应。用于按 queryId 查看一次检索的查询、上下文和命中证据。
 */
export interface RetrievalAuditDetail {
  /** 审计追踪号，对应后端 queryId。 */
  queryId: string;
  /** 租户标识。 */
  tenantId: string;
  /** 主体类型，未登录时可能为空。 */
  subjectType?: string;
  /** 主体 ID，未登录时可能为空。 */
  subjectId?: string;
  /** 原始检索词。 */
  queryText: string;
  /** 总检索策略。 */
  retrievalStrategy: string;
  /** 请求的 Top K。 */
  requestedLimit: number;
  /** 实际命中数量。 */
  hitCount: number;
  /** 后端检索耗时毫秒。 */
  elapsedMs: number;
  /** 请求上下文，包含 endpoint、IP、设备和 UA 等线索。 */
  requestContext: RetrievalRequestContextAudit;
  /** 按 rankNo 排序的命中审计列表。 */
  hits: RetrievalAuditHit[];
}

/**
 * 登录请求。身份由后端用户名密码校验产生，前端不能自报 userId/role/studentId。
 */
export interface LoginRequest {
  /** 后端账号名。 */
  username: string;
  /** 后端账号密码。 */
  password: string;
}

/**
 * Public student registration request. The backend always creates a student role.
 */
export interface RegisterRequest {
  /** Unique login username. */
  username: string;
  /** Password sent to the backend over the current HTTP connection. */
  password: string;
  /** Optional tenant id; backend defaults it when omitted. */
  tenantId?: string;
}

/**
 * 登录响应。tokenName/tokenValue 由 Sa-Token 生成，后续请求按后端要求携带。
 */
export interface LoginResponse {
  /** 后端可信用户 ID。 */
  userId: string;
  /** 登录账号名。 */
  username: string;
  /** 后端会话角色。 */
  role: string;
  /** 后端会话租户。 */
  tenantId: string;
  /** Sa-Token token 名称。 */
  tokenName: string;
  /** Sa-Token token 值。 */
  tokenValue: string;
}

/**
 * 教学任务提交请求。clientRequestId 由前端生成并持久化，用于重复提交时恢复同一任务。
 */
export interface TeachingTaskRequest {
  /** 前端幂等请求号，刷新或重试时保持不变。 */
  clientRequestId: string;
  /** 用户输入的题目或学习问题。 */
  questionText?: string;
  /** 用户想学什么。 */
  learningGoal: string;
  /** 教材证据召回数量上限。 */
  evidenceLimit: number;
  /** Optional backend-owned handout template code selected by the user. */
  handoutTemplateCode?: string;
}

export interface TeachingHandoutTemplateResponse {
  templateCode: string;
  displayName: string;
  sourceType: string;
  audience: string;
  description: string;
  category?: string;
  visualStyle?: string;
  difficultyBands?: string[];
  tags?: string[];
  referenceTitle?: string | null;
  referencePath?: string | null;
  referencePreview?: string | null;
}

export interface TeachingHandoutPdfResponse {
  bytes: Uint8Array;
  renderer: string;
  pageCount: number;
}

/**
 * One-time capability token returned before a high-value operation.
 */
export interface CapabilityTokenResponse {
  /** Opaque token that must be consumed by the matching high-value request. */
  token: string;
  /** High-value action code bound to this token. */
  action: string;
  /** API path bound to this token. */
  path: string;
  /** Stable digest of the exact request body that will consume the token. */
  requestHash: string;
  /** Backend expiration timestamp. */
  expiresAt: string;
}

/**
 * Capability audit query filters. Tenant and reviewer identity are resolved by the backend session.
 */
export interface CapabilityAuditQuery {
  /** Optional audited subject role filter, such as student or teacher. */
  subjectType?: string;
  /** Optional audited subject id filter. */
  subjectId?: string;
  /** Optional high-value action code filter. */
  action?: string;
  /** Optional lifecycle decision filter, such as issued, consumed, rejected, or denied. */
  decision?: string;
  /** Maximum rows returned by the backend. */
  limit?: number;
}

/**
 * Capability audit row returned to teacher/admin reviewers.
 */
export interface CapabilityAuditLogResponse {
  /** Stable audit event id. */
  eventId: string;
  /** Backend event timestamp when present. */
  occurredAt?: string;
  /** Tenant that owns the event. */
  tenantId: string;
  /** Backend resolved requester role. */
  subjectType?: string;
  /** Backend resolved requester id. */
  subjectId?: string;
  /** High-value action code. */
  action: string;
  /** API path bound to the capability. */
  path: string;
  /** Hash of the exact high-value request body. */
  requestHash: string;
  /** Client idempotency key. */
  idempotencyKey: string;
  /** SHA-256 token hash; raw capability tokens are never returned. */
  tokenHash: string;
  /** Lifecycle decision, such as issued, consumed, rejected, or denied. */
  decision: string;
  /** Human-readable decision reason. */
  reason: string;
}

/**
 * 教学 DAG 节点执行记录。
 */
export interface TeachingWorkflowNode {
  /** DAG 节点稳定编码。 */
  code: string;
  /** 节点显示名称。 */
  name: string;
  /** 节点执行状态。 */
  status: string;
  /** 节点输出摘要。 */
  summary: string;
}

/**
 * ReAct 解题轨迹步骤。
 */
export interface TeachingReactStep {
  /** THOUGHT/ACTION/OBSERVATION/ANSWER。 */
  phase: string;
  /** 当前步骤内容。 */
  content: string;
  /** ACTION 阶段调用的工具名。 */
  toolName?: string;
}

/**
 * 教学任务证据，明确区分公开教材和后续私有飞书资料。
 */
export interface TeachingEvidence {
  /** 证据作用域，例如 PUBLIC_TEXTBOOK。 */
  sourceScope: string;
  /** 证据来源标题。 */
  sourceTitle: string;
  /** 证据 chunk ID。 */
  chunkId: string;
  /** 教材页码。 */
  pageNo: number;
  /** 证据片段。 */
  snippet: string;
}

/**
 * 教学任务中的学生记忆复用摘要，用于展示是否复用了私有或公开历史答案。
 */
export interface TeachingMemoryReuse {
  /** 是否复用了历史答案。 */
  reused: boolean;
  /** 命中的记忆 ID；未命中时为空。 */
  memoryId?: string;
  /** 复用作用域，private 表示学生私有，public 表示租户内公开复用。 */
  reuseScope?: string;
  /** 可复用答案文本；未命中时为空。 */
  answer?: string;
  /** 相似度分数，范围 0 到 1。 */
  similarity: number;
  /** 复用或不复用原因。 */
  reason: string;
}

/**
 * 教学任务阶段耗时，用于前端状态、工具调用和性能面板。
 */
/**
 * Request body for student memory remember operations. Identity is resolved by the backend session.
 */
export interface StudentMemoryRequest {
  /** Student question text used for future similarity matching. */
  questionText: string;
  /** Generated answer text to store as memory. */
  answerText: string;
  /** Optional knowledge point label used to improve matching precision. */
  knowledgePointName?: string;
  /** Requested scope; backend downgrades unprivileged public writes to private. */
  memoryScope?: "private" | "public";
  /** Whether the caller explicitly bypassed reuse before generating this answer. */
  bypassReuse?: boolean;
}

/**
 * Student memory remember/reuse response returned by the backend memory service.
 */
export interface StudentMemoryResponse {
  /** Whether a previous memory answer was reused. */
  reused: boolean;
  /** Memory entry id when a memory was stored or matched. */
  memoryId?: string;
  /** Effective memory scope after backend normalization. */
  reuseScope?: string;
  /** Stored or reused answer text. */
  answer?: string;
  /** Similarity or write confidence score from 0 to 1. */
  similarity: number;
  /** Backend decision reason. */
  reason: string;
  /** Backend stage timing rows for performance review. */
  stageTimings?: TeachingStageTiming[];
}

export interface TeachingStageTiming {
  /** 阶段编码，例如 memory_reuse、textbook_retrieval。 */
  stage: string;
  /** 当前阶段耗时毫秒数。 */
  elapsedMs: number;
}

export interface TeachingAiDraft {
  enabled: boolean;
  providerName: string;
  modelCode: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  content: string;
  message: string;
  structured: boolean;
  teacherExplanation: string;
  studentHint: string;
  knowledgePoints: string[];
  followUpQuestions: string[];
  parseError: string;
  retryCount: number;
  maxRetries: number;
  recoveredAfterRetry: boolean;
  recoveryEvents: TeachingAiRecoveryEvent[];
}

export interface TeachingAiRecoveryEvent {
  eventType: string;
  providerName: string;
  modelCode: string;
  attemptNo: number;
  structured: boolean;
  retryable: boolean;
  message: string;
}

/**
 * 教学任务响应。taskId 可保存到 localStorage，用户离开页面后继续恢复结果。
 */
export interface TeachingTaskResponse {
  /** 后端任务 ID。 */
  taskId: string;
  /** 前端幂等请求号。 */
  clientRequestId: string;
  /** 租户 ID。 */
  tenantId?: string;
  /** 主体类型。 */
  subjectType?: string;
  /** 主体 ID。 */
  subjectId?: string;
  /** Selected backend-owned handout template. */
  selectedTemplate?: TeachingHandoutTemplateResponse;
  /** 任务状态。 */
  status: "CREATED" | "RUNNING" | "COMPLETED" | "FAILED";
  /** 用户题目。 */
  questionText?: string;
  /** 学习目标。 */
  learningGoal?: string;
  /** DAG 节点。 */
  nodes: TeachingWorkflowNode[];
  /** ReAct 轨迹。 */
  reactTrace: TeachingReactStep[];
  /** 证据列表。 */
  evidence: TeachingEvidence[];
  /** LaTeX 讲义草稿。 */
  handoutLatex: string;
  /** Teacher version with explanation, method notes, and knowledge point attribution. */
  teacherHandoutLatex?: string;
  /** Student version with blanks and prompts but without detailed teacher-only answers. */
  studentHandoutLatex?: string;
  /** 后续交互建议。 */
  interactiveSuggestions: string[];
  /** 学生记忆复用决策。 */
  memoryReuse?: TeachingMemoryReuse;
  /** 后端 DAG 阶段耗时统计。 */
  stageTimings?: TeachingStageTiming[];
  aiDraft?: TeachingAiDraft;
  /** 失败原因。 */
  errorMessage?: string;
}

/**
 * Request for creating a temporary ZIP of teaching handouts.
 */
export interface TeachingHandoutBatchExportRequest {
  /** Selected task ids; backend reloads each task through session ownership checks. */
  taskIds: string[];
  /** Optional folder ids used for audit and future backend folder expansion. */
  folderIds?: string[];
  /** Optional folder paths used as ZIP entry prefixes. */
  folderPaths?: string[];
}

/**
 * Temporary ZIP metadata returned by the backend.
 */
export interface TeachingHandoutBatchExportResponse {
  /** Temporary batch id used by the protected download endpoint. */
  batchId: string;
  /** Export status; temporary ZIP export returns COMPLETED synchronously. */
  status: string;
  /** Backend-resolved requester role that controls whether teacher handouts are packaged. */
  subjectType: string;
  /** Number of requested task ids. */
  requestedCount: number;
  /** Number of owned tasks exported into the ZIP. */
  exportedCount: number;
  /** Backend-owned task ids included in the ZIP. */
  taskIds: string[];
  /** Folder ids captured for audit. */
  folderIds: string[];
  /** Folder paths used inside the ZIP. */
  folderPaths: string[];
  /** Backend expiration timestamp for the temporary ZIP. */
  expiresAt: string;
}

/**
 * Human feedback request for a generated teaching task.
 */
export interface TeachingHumanFeedbackRequest {
  /** Numeric feedback score from 1 to 5. */
  rating: number;
  /** Compact decision code, such as helpful, confusing, or needs_revision. */
  decision: string;
  /** Human-readable feedback content for later review. */
  comment: string;
  /** Structured handout review context captured by the frontend review panel. */
  reviewContext?: Record<string, unknown>;
}

/**
 * Human feedback record returned after backend ownership and capability checks.
 */
export interface TeachingHumanFeedbackResponse {
  /** Backend-generated feedback id. */
  feedbackId: string;
  /** Teaching task id that received feedback. */
  taskId: string;
  /** Backend tenant id resolved from session. */
  tenantId: string;
  /** Backend subject role resolved from session. */
  subjectType: string;
  /** Backend subject id resolved from session. */
  subjectId: string;
  /** Numeric feedback score from 1 to 5. */
  rating: number;
  /** Compact decision code. */
  decision: string;
  /** Free-text feedback content. */
  comment: string;
  /** Structured handout review context captured at submission time. */
  reviewContext?: Record<string, unknown>;
  /** Backend creation timestamp. */
  createdAt: string;
}

/**
 * Request for planning an AI agent run before model/tool execution.
 */
export interface AgentRunPlanRequest {
  /** Requested agent code, such as StudentTutorAgent or CoursewareAgent. */
  agentCode: string;
  /** Task type used for route selection. */
  taskType: string;
  /** Caller tier used for token and model budget limits. */
  userVipLevel: string;
  /** Estimated input tokens before backend clipping. */
  estimatedInputTokens: number;
  /** Estimated output tokens before backend clipping. */
  estimatedOutputTokens: number;
  /** Whether the task includes image input. */
  hasImage: boolean;
  /** Whether the task contains formulas. */
  hasFormula: boolean;
  /** Difficulty label used for model routing. */
  difficulty: string;
  /** Latency preference, such as low or normal. */
  latencyRequirement: string;
  /** Cost budget for the planned run. */
  costBudget: number;
  /** Recent failure count used for fallback routing. */
  previousFailureCount: number;
  /** Whether the output must satisfy a JSON schema. */
  requiredJsonSchema: boolean;
  /** Tool scopes requested by the workflow. */
  requestedToolScopes: string[];
  /** Per-request tool scopes the user disabled; backend only removes tools and never grants access from this field. */
  disabledToolScopes: string[];
  /** Data scopes requested by the workflow. */
  requestedDataScopes: string[];
  /** Whether this run can spend high-value model/tool budget. */
  highValueOperation: boolean;
  /** Optional provider preference selected by UI; backend validates it before use. */
  preferredProviderName?: string;
  /** Optional model preference selected by UI; backend validates it against provider allow-list. */
  preferredModelCode?: string;
}

/**
 * Safe AI agent execution plan returned by the backend.
 */
export interface AgentRunPlanResponse {
  /** Plan id used for later trace linking. */
  planId: string;
  /** Backend resolved tenant id. */
  tenantId: string;
  /** Backend resolved subject type. */
  subjectType: string;
  /** Backend resolved subject id. */
  subjectId: string;
  /** Selected agent code. */
  agentCode: string;
  /** Selected provider name. */
  providerName: string;
  /** Selected model code. */
  modelCode: string;
  /** Selected model capability level. */
  modelLevel: string;
  /** Tool scopes accepted by policy. */
  allowedToolScopes: string[];
  /** Tool scopes rejected by policy. */
  deniedToolScopes: string[];
  /** Per-tool backend decisions explaining dynamic tool injection filtering. */
  toolPolicyDecisions: AgentToolPolicyDecision[];
  /** Data scopes accepted by policy. */
  allowedDataScopes: string[];
  /** Data scopes rejected by policy. */
  deniedDataScopes: string[];
  /** Whether execution requires a capability token. */
  capabilityRequired: boolean;
  /** Capability action to request when required. */
  capabilityAction: string;
  /** Policy-clipped input token limit. */
  maxInputTokens: number;
  /** Policy-clipped output token limit. */
  maxOutputTokens: number;
  /** Estimated total tokens after clipping. */
  estimatedTotalTokens: number;
  /** Local deterministic cost estimate. */
  estimatedCost: number;
  /** Whether the cost estimate is inside budget. */
  withinBudget: boolean;
  /** Human-readable route reason for audit. */
  routeReason: string;
  /** Stage timings for monitoring. */
  stageTimings: TeachingStageTiming[];
  /** Redis-style concurrency keys for later execution. */
  concurrencyKeys: string[];
  /** Whether the executor must validate model output as a JSON object and retry repair on parse failure. */
  requiredJsonSchema: boolean;
}

/**
 * Backend-owned model catalog used by the frontend provider/model controls.
 */
export interface AgentModelCatalogResponse {
  /** Backend default provider from environment. */
  defaultProviderName: string;
  /** Backend default model from environment. */
  defaultModelCode: string;
  /** Backend fallback provider rotation order. */
  fallbackProviderOrder: string[];
  /** Enabled providers and their allow-listed model options. */
  providers: AgentModelProvider[];
}

/**
 * One enabled AI provider in the backend model catalog.
 */
export interface AgentModelProvider {
  /** Provider code, such as openai or dashscope. */
  name: string;
  /** Whether backend credentials are configured. */
  enabled: boolean;
  /** Provider default model code. */
  defaultModelCode: string;
  /** Allow-listed models accepted by backend planning. */
  models: AgentModelOption[];
}

/**
 * One backend allow-listed model option.
 */
export interface AgentModelOption {
  /** Provider model code sent to the OpenAI-compatible API. */
  modelCode: string;
  /** Coarse capability level for display. */
  modelLevel: string;
  /** Coarse price label for display. */
  priceTier: string;
}

/**
 * Safe backend AI provider health response.
 */
export interface AgentModelHealthResponse {
  /** Backend timestamp when checks started. */
  checkedAt: string;
  /** Per-provider health rows. */
  results: AgentModelHealthResult[];
}

/**
 * One provider/model health row without keys, prompts, or raw model output.
 */
export interface AgentModelHealthResult {
  /** Provider code, such as openai or dashscope. */
  providerName: string;
  /** Model code checked by backend. */
  modelCode: string;
  /** Whether backend credentials are configured. */
  configured: boolean;
  /** Whether the provider answered the real health request. */
  reachable: boolean;
  /** HTTP-style status when known. */
  statusCode?: number;
  /** Backend measured elapsed milliseconds. */
  elapsedMs: number;
  /** Short safe status reason. */
  safeReason: string;
  /** Backend timestamp for this provider row. */
  checkedAt: string;
}

/**
 * Backend decision for one requested agent tool scope.
 */
export interface AgentToolPolicyDecision {
  /** Requested tool scope. */
  scope: string;
  /** Stable backend decision code. */
  decision: "ALLOWED" | "DISABLED_BY_USER" | "DENIED_BY_AGENT_POLICY";
  /** Human-readable audit reason. */
  reason: string;
}

/**
 * Request body for executing a planned AI agent run.
 */
export interface AgentRunExecuteRequest {
  /** Plan returned by backend planning; backend still rechecks identity and high-value policy. */
  plan: AgentRunPlanResponse;
  /** Short user task summary for trace audit. */
  userInputSummary: string;
  /** Evidence ids or resource anchors used by this run. */
  evidenceRefs: string[];
}

/**
 * Safe live execution response; raw prompt and full model output are intentionally omitted.
 */
export interface AgentRunExecuteResponse {
  /** Trace id used for later monitoring and audit. */
  traceId: string;
  /** Source plan id. */
  planId: string;
  /** Backend resolved tenant id. */
  tenantId: string;
  /** Backend resolved subject type. */
  subjectType: string;
  /** Backend resolved subject id. */
  subjectId: string;
  /** Executed agent code. */
  agentCode: string;
  /** Selected provider name. */
  providerName: string;
  /** Selected model code. */
  modelCode: string;
  /** Execution status. */
  status: string;
  /** Estimated local cost copied from the plan. */
  estimatedCost: number;
  /** Tool scopes recorded for audit. */
  allowedToolScopes: string[];
  /** Data scopes recorded for audit. */
  allowedDataScopes: string[];
  /** Concurrency keys acquired for this run. */
  concurrencyKeys: string[];
  /** Execution stage timings. */
  stageTimings: TeachingStageTiming[];
  /** Provider-reported token usage for live model calls. */
  actualUsage: AgentTokenUsage;
  /** Safe status message. */
  message: string;
}

/**
 * Provider-reported token usage for a real AI call.
 */
export interface AgentTokenUsage {
  /** Input tokens reported by provider. */
  promptTokens: number;
  /** Output tokens reported by provider. */
  completionTokens: number;
  /** Total tokens reported by provider. */
  totalTokens: number;
}

/**
 * Request for protected multi-agent courseware or handout writing.
 */
export interface MultiAgentWritingRequest {
  /** Writing goal, such as teacher handout, student blank handout, or courseware outline. */
  writingGoal: string;
  /** Source question or teaching topic submitted by the teacher. */
  questionText: string;
  /** Evidence anchors selected from textbook, Feishu, question bank, or teacher resources. */
  evidenceRefs: string[];
  /** Optional preferred provider name; backend validates it before use. */
  preferredProviderName?: string;
  /** Optional preferred model code; backend validates it against provider allow-list. */
  preferredModelCode?: string;
}

/**
 * One stage result from the backend-owned multi-agent writing workflow.
 */
export interface MultiAgentWritingStageResult {
  /** Stable stage code, such as draft, review, or format. */
  stageCode: string;
  /** Backend agent code executed for this stage. */
  agentCode: string;
  /** Trace id used for recovery and diagnostics. */
  traceId: string;
  /** Actual provider used after backend fallback rotation. */
  providerName: string;
  /** Actual model used after backend fallback rotation. */
  modelCode: string;
  /** Stage execution status. */
  status: string;
  /** Provider-reported token usage for this stage. */
  actualUsage: AgentTokenUsage;
  /** Safe status message without raw prompt or full model output. */
  message: string;
}

/**
 * Safe multi-agent writing workflow status response.
 */
export interface MultiAgentWritingResponse {
  /** Backend workflow id used for recovery after leaving the page. */
  workflowId: string;
  /** Backend tenant id. */
  tenantId: string;
  /** Backend subject role. */
  subjectType: string;
  /** Backend subject id. */
  subjectId: string;
  /** Workflow status, such as RUNNING, COMPLETED, or FAILED. */
  status: string;
  /** Backend workflow creation time. */
  createdAt?: string;
  /** Backend latest workflow update time. */
  updatedAt?: string;
  /** Ordered stage results completed so far. */
  stages: MultiAgentWritingStageResult[];
  /** Summed provider-reported token usage. */
  totalUsage: AgentTokenUsage;
  /** Safe workflow status message without raw prompt or full model output. */
  message?: string;
}

/**
 * Owner-visible generated content for one multi-agent writing workflow.
 */
export interface MultiAgentWritingArtifact {
  /** Backend workflow id. */
  workflowId: string;
  /** Backend tenant id. */
  tenantId: string;
  /** Backend subject role. */
  subjectType: string;
  /** Backend subject id. */
  subjectId: string;
  /** Workflow status. */
  status: string;
  /** Summed provider-reported token usage. */
  totalUsage: AgentTokenUsage;
  /** Per-stage generated content. */
  stages: MultiAgentWritingStageArtifact[];
  /** Merged Markdown content for preview and export. */
  mergedMarkdown: string;
}

/**
 * Generated content from one writing stage.
 */
export interface MultiAgentWritingStageArtifact {
  /** Stable stage code. */
  stageCode: string;
  /** Backend agent code. */
  agentCode: string;
  /** Trace id used for diagnostics. */
  traceId: string;
  /** Actual provider used by this stage. */
  providerName: string;
  /** Actual model used by this stage. */
  modelCode: string;
  /** Stage status. */
  status: string;
  /** Owner-visible generated content. */
  generatedContent: string;
}

/**
 * Temporary exported artifact payload.
 */
export interface MultiAgentWritingArtifactExportResponse {
  exportId: string;
  workflowId: string;
  format: "markdown" | "latex" | "pdf" | "zip" | string;
  fileName: string;
  mimeType: string;
  byteSize: number;
  sha256: string;
  base64Content: string;
  expiresAt: string;
}

/**
 * Optional filters for listing visible agent traces.
 */
export interface AgentTraceQuery {
  /** Optional agent code filter. */
  agentCode?: string;
  /** Optional execution status filter. */
  status?: string;
  /** Optional plan id filter; teaching AI traces use taskId as planId. */
  planId?: string;
  /** Optional plan id prefix filter; multi-agent writing uses workflowId:stageCode. */
  planIdPrefix?: string;
  /** Maximum rows to return. */
  limit?: number;
}

/**
 * Safe trace row returned for recovery and monitoring.
 */
export interface AgentTraceResponse {
  /** Trace id. */
  traceId: string;
  /** Linked plan id. */
  planId: string;
  /** Trace creation time. */
  createdAt: string;
  /** Backend tenant id. */
  tenantId: string;
  /** Backend subject type. */
  subjectType: string;
  /** Backend subject id. */
  subjectId: string;
  /** Executed agent code. */
  agentCode: string;
  /** Selected provider. */
  providerName: string;
  /** Selected model code. */
  modelCode: string;
  /** Execution status. */
  status: string;
  /** Estimated local cost. */
  estimatedCost: number;
  /** Allowed tool scopes recorded for audit. */
  allowedToolScopes: string[];
  /** Allowed data scopes recorded for audit. */
  allowedDataScopes: string[];
  /** Evidence references used by this run. */
  evidenceRefs: string[];
  /** Persisted execution stage timings for recovery views. */
  stageTimings: TeachingStageTiming[];
  /** Provider-reported token usage persisted by backend trace metadata. */
  actualUsage: AgentTokenUsage;
  /** Safe execution message without raw prompt or raw model output. */
  message: string;
  /** Safe retry/fallback/parse diagnostics without raw prompts or model outputs. */
  diagnosticEvents?: AgentTraceDiagnosticEvent[];
}

/**
 * Safe trace recovery response for a multi-agent writing workflow.
 */
export interface MultiAgentWritingTraceResponse {
  /** Backend workflow id. */
  workflowId: string;
  /** Backend tenant id. */
  tenantId: string;
  /** Backend subject role. */
  subjectType: string;
  /** Backend subject id. */
  subjectId: string;
  /** Number of visible stage traces. */
  stageCount: number;
  /** Summed provider-reported token usage for visible traces. */
  totalUsage: AgentTokenUsage;
  /** Ordered safe stage traces. */
  stages: AgentTraceResponse[];
}

export interface AgentTraceDiagnosticEvent {
  /** Stable diagnostic event code. */
  eventType: string;
  /** Provider involved in this event. */
  providerName: string;
  /** Model involved in this event. */
  modelCode: string;
  /** Zero-based attempt number when applicable. */
  attemptNo: number;
  /** Whether retry or fallback was still available after this event. */
  retryable: boolean;
  /** Short safe event message. */
  message: string;
}

/**
 * 学生学习画像响应。字段与后端 `StudentDashboardResponse` 对齐，用于学生端进度图谱、薄弱点和历史记录展示。
 */
/**
 * Aggregated provider-reported token usage for visible agent traces.
 */
export interface AgentTraceUsageSummaryResponse {
  /** Backend tenant id used for the aggregation. */
  tenantId: string;
  /** Backend subject type used for visibility. */
  subjectType: string;
  /** Backend subject id used for visibility. */
  subjectId: string;
  /** Optional agent code filter echoed by backend. */
  agentCode?: string;
  /** Optional status filter echoed by backend. */
  status?: string;
  /** Visible trace rows included in the summary. */
  runCount: number;
  /** Total official provider usage. */
  totalUsage: AgentTokenUsage;
  /** Provider/model usage breakdown. */
  modelUsages: AgentTraceModelUsage[];
}

/**
 * Aggregated safe retry/fallback/parse diagnostics for visible agent traces.
 */
export interface AgentTraceDiagnosticSummaryResponse {
  /** Backend tenant id used for the aggregation. */
  tenantId: string;
  /** Backend subject type used for visibility. */
  subjectType: string;
  /** Backend subject id used for visibility. */
  subjectId: string;
  /** Optional agent code filter echoed by backend. */
  agentCode?: string;
  /** Optional status filter echoed by backend. */
  status?: string;
  /** Visible trace rows included in the summary. */
  runCount: number;
  /** Total safe diagnostic events included. */
  diagnosticEventCount: number;
  /** JSON parse failure events. */
  jsonParseFailureCount: number;
  /** Retry scheduled events. */
  retryScheduledCount: number;
  /** Traces recovered after retry. */
  retryRecoveredCount: number;
  /** Provider fallback rotation events. */
  providerRotationCount: number;
  /** Model gateway failure events. */
  modelCallFailureCount: number;
  /** Provider/model diagnostic breakdown. */
  modelDiagnostics: AgentTraceModelDiagnostic[];
}

/**
 * Diagnostic bucket for one provider/model pair.
 */
export interface AgentTraceModelDiagnostic {
  /** Provider name recorded by backend events. */
  providerName: string;
  /** Model code recorded by backend events. */
  modelCode: string;
  /** Visible trace rows in this bucket. */
  runCount: number;
  /** Safe diagnostic events in this bucket. */
  diagnosticEventCount: number;
  /** JSON parse failures in this bucket. */
  jsonParseFailureCount: number;
  /** Scheduled retries in this bucket. */
  retryScheduledCount: number;
  /** Traces recovered after retry in this bucket. */
  retryRecoveredCount: number;
  /** Provider fallback rotations in this bucket. */
  providerRotationCount: number;
  /** Model gateway failures in this bucket. */
  modelCallFailureCount: number;
  /** Provider-reported total tokens for visible trace rows. */
  totalTokens: number;
}

/**
 * Usage bucket for one provider/model pair.
 */
export interface AgentTraceModelUsage {
  /** Provider name recorded by backend execution. */
  providerName: string;
  /** Model code recorded by backend execution. */
  modelCode: string;
  /** Visible trace rows in this bucket. */
  runCount: number;
  /** Summed prompt tokens. */
  promptTokens: number;
  /** Summed completion tokens. */
  completionTokens: number;
  /** Summed total tokens. */
  totalTokens: number;
}

/**
 * Backend-generated MCP configuration template and layered usage notes.
 */
export interface McpConfigurationResponse {
  /** Stable MCP server name. */
  serverName: string;
  /** Validated public MCP URL. */
  url: string;
  /** Whether backend generation succeeded. */
  valid: boolean;
  /** Whether the referenced backend-owned key is active and usable. */
  secretKeyAccepted: boolean;
  /** Redacted secret preview for the backend-owned key. */
  secretKeyPreview: string;
  /** Environment variable name used in copied JSON. */
  secretEnvName: string;
  /** Backend-derived profile such as teacher or student. */
  keyProfile: string;
  /** Final exposed tool names after backend filtering. */
  exposedTools: string[];
  /** Final exposed prompt names after backend filtering. */
  exposedPrompts: string[];
  /** Copyable MCP JSON template. */
  configJson: string;
  /** Layered MCP usage explanation. */
  layers: McpConfigurationLayer[];
}

/**
 * One MCP key owned by the current authenticated backend account.
 */
export interface McpClientKeyResponse {
  keyId: string;
  name: string;
  tenantId: string;
  ownerUserId: string;
  keyProfile: string;
  status: string;
  secretKeyPreview: string;
  createdAt?: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
}

/**
 * Backend-generated MCP key returned once with its raw secret.
 */
export interface McpClientKeyCreatedResponse {
  keyId: string;
  name: string;
  tenantId: string;
  ownerUserId: string;
  keyProfile: string;
  secretKey: string;
  secretKeyPreview: string;
  configuration: McpConfigurationResponse;
}

/**
 * Revocation result for one owned MCP key.
 */
export interface McpClientKeyRevocationResponse {
  keyId: string;
  status: string;
  revokedAt?: string | null;
}

/**
 * One MCP usage layer displayed in the frontend.
 */
export interface McpConfigurationLayer {
  /** Stable layer code. */
  code: string;
  /** Layer display name. */
  name: string;
  /** Layer description. */
  description: string;
  /** Credential expected by this layer. */
  requiredCredential: string;
  /** Operations allowed in this layer. */
  allowedOperations: string[];
}

/**
 * Result of a real standard MCP connection smoke test from the browser.
 */
export interface McpConnectionTestResult {
  /** HTTP MCP endpoint that was tested. */
  url: string;
  /** Protocol version requested and echoed by the server. */
  protocolVersion: string;
  /** Server name returned by initialize. */
  serverName: string;
  /** Server version returned by initialize. */
  serverVersion: string;
  /** Tool names returned by tools/list for this Bearer secret. */
  tools: string[];
  /** Number of tools visible to this MCP key. */
  toolCount: number;
}

export interface StudentDashboardResponse {
  /** 租户 ID，用于学校或机构维度的数据隔离。 */
  tenantId: string;
  /** 当前面板展示的学生 ID。 */
  studentId: string;
  /** 当前面板代表对象的后端角色，例如 student、teacher、admin、global 或 unknown。 */
  subjectRole: string;
  /** 当前查看者角色，通常为 student、teacher 或 admin。 */
  viewerRole: string;
  /** 当前查看者主体 ID。 */
  viewerSubjectId: string;
  /** 是否为教师或管理员代查学生画像。 */
  isAdminView: boolean;
  /** 按教材和飞书锚点组织的知识点掌握进度。 */
  knowledgeProgress: StudentKnowledgeProgress[];
  /** 从做题、试卷和教学任务中汇总的薄弱知识点。 */
  weakPoints: StudentWeakPoint[];
  /** 可恢复的历史问题记录。 */
  recentQuestions: StudentRecentQuestion[];
  /** 历史成绩趋势和年级排名。 */
  scoreTrend: StudentScorePoint[];
  /** 当前学生可访问的资源域。 */
  resourceScopes: StudentResourceScope[];
  /** Backend-assembled knowledge graph for mastery visualization. */
  knowledgeGraph?: StudentKnowledgeGraph;
}

/**
 * Student knowledge graph returned with the dashboard.
 */
export interface StudentKnowledgeGraph {
  /** Graph nodes visible to the current viewer. */
  nodes: StudentKnowledgeGraphNode[];
  /** Directed relations between knowledge points. */
  edges: StudentKnowledgeGraphEdge[];
  /** Source summary used for audit and display. */
  generatedFrom: string;
}

/**
 * One visible knowledge graph node.
 */
export interface StudentKnowledgeGraphNode {
  /** Stable knowledge point id. */
  knowledgePointId: string;
  /** Knowledge point display name. */
  knowledgePointName: string;
  /** Textbook chapter or section path. */
  chapterPath: string;
  /** Student mastery percent from 0 to 100. */
  masteryPercent: number;
  /** Risk label derived from weak point and mastery evidence. */
  riskLevel: string;
  /** Evidence links the current viewer can open or search. */
  evidenceLinks: StudentKnowledgeEvidenceLink[];
}

/**
 * One directed knowledge graph edge.
 */
export interface StudentKnowledgeGraphEdge {
  /** Stable edge id. */
  edgeId: string;
  /** Source knowledge point id. */
  sourceKnowledgePointId: string;
  /** Target knowledge point id. */
  targetKnowledgePointId: string;
  /** Relation type such as PREREQUISITE_FOR or RELATED_TO. */
  relationType: string;
  /** Evidence summary behind the relation. */
  evidenceSummary: string;
}

/**
 * Evidence link attached to a knowledge graph node.
 */
export interface StudentKnowledgeEvidenceLink {
  /** Evidence source type such as textbook, feishu, or teacher_resource. */
  sourceType: string;
  /** Evidence display title. */
  title: string;
  /** Evidence URL or internal retrieval path. */
  url: string;
  /** Permission scope required by the evidence. */
  permissionScope: string;
}

/**
 * Request body for student-side question explanation cards.
 */
export interface StudentExplanationRequest {
  /** Durable conversation id returned by a previous explanation response. */
  conversationId?: string;
  /** Question text typed by the student or produced by a real OCR/vision step. */
  questionText?: string;
  /** Backend-issued temporary upload id from the real image upload endpoint. */
  imageUploadId?: string;
  /** Optional image file name; this is metadata only unless OCR is configured on the backend. */
  imageFileName?: string;
  /** Optional image MIME type. */
  imageContentType?: string;
  /** Optional image size in bytes. */
  imageSizeBytes?: number;
  /** Allows the backend to search public textbook resources. */
  searchTextbook?: boolean;
  /** Allows the backend to match the curated display knowledge graph. */
  searchKnowledgeGraph?: boolean;
  /** Requests teacher resource search; backend still restricts this to teacher/admin subjects. */
  searchTeacherResources?: boolean;
  /** Maximum textbook hits used by the card orchestrator. */
  maxTextbookHits?: number;
  /** Maximum teacher resource hits used by the card orchestrator. */
  maxTeacherResourceHits?: number;
}

/**
 * Response returned after uploading a real temporary image binary for student explanation.
 */
export interface StudentExplanationImageUploadResponse {
  /** Backend-issued temporary upload id used by the explanation request. */
  uploadId: string;
  /** Sanitized original file name. */
  originalFileName: string;
  /** Validated image MIME type. */
  contentType: string;
  /** Stored file size in bytes. */
  sizeBytes: number;
  /** Backend expiration timestamp for the temporary file. */
  expiresAt: string;
  /** Explicit status; upload does not mean OCR has run. */
  imageStatus: string;
}

/**
 * Compact durable explanation history for resuming a student conversation.
 */
export interface StudentExplanationHistoryResponse {
  /** Recent history items visible to the backend-resolved subject. */
  items: StudentExplanationHistoryItem[];
}

/**
 * One durable explanation history item.
 */
export interface StudentExplanationHistoryItem {
  explanationId: string;
  conversationId: string;
  questionText?: string;
  imageStatus: string;
  imageProblemText?: string;
  aiProviderName: string;
  aiModelCode: string;
  totalTokens: number;
  totalElapsedMs: number;
  createdAt: string;
}

/**
 * Backend response for student-side explanation cards.
 */
export interface StudentExplanationResponse {
  /** Server-generated explanation id for trace and retry correlation. */
  explanationId: string;
  /** Durable conversation id for follow-up context and history recovery. */
  conversationId: string;
  /** Backend-resolved tenant id. */
  tenantId: string;
  /** Backend-resolved student id when the viewer is a student. */
  studentId?: string;
  /** Backend-resolved viewer role. */
  viewerRole: string;
  /** Normalized question text used by retrieval. */
  questionText?: string;
  /** Image handling status; never means OCR unless backend says so explicitly. */
  imageStatus: string;
  /** Safe metadata from the real vision/OCR image understanding stage. */
  imageUnderstanding: StudentExplanationImageUnderstanding;
  /** Orchestrator name and version. */
  generatedBy: string;
  /** Safe AI generation metadata for model, token, parse, and fallback status. */
  aiDraft: StudentExplanationAiDraft;
  /** DAG stage states with timing and skip/failure detail. */
  workflowStages: StudentExplanationStage[];
  /** Frontend-ready explanation cards. */
  cards: StudentExplanationCard[];
  /** Source anchors used by cards. */
  sources: StudentExplanationSource[];
  /** Total backend elapsed time in milliseconds. */
  totalElapsedMs: number;
}

/**
 * Safe metadata from the backend image understanding stage.
 */
export interface StudentExplanationImageUnderstanding {
  /** Whether a real vision model call was attempted. */
  enabled: boolean;
  /** Whether visible problem text was extracted. */
  succeeded: boolean;
  /** Provider used by the vision stage. */
  providerName: string;
  /** Model used by the vision stage. */
  modelCode: string;
  /** Extracted visible problem text. */
  problemText: string;
  /** Model confidence from 0 to 1. */
  confidence: number;
  /** Provider-reported prompt tokens. */
  promptTokens: number;
  /** Provider-reported completion tokens. */
  completionTokens: number;
  /** Provider-reported total tokens. */
  totalTokens: number;
  /** Safe status message. */
  message: string;
}

/**
 * One explanation workflow stage.
 */
export interface StudentExplanationStage {
  /** Stable stage key. */
  stageKey: string;
  /** Short display title. */
  title: string;
  /** completed, skipped, or failed. */
  status: string;
  /** Stage detail or failure reason. */
  detail: string;
  /** Stage elapsed time in milliseconds. */
  elapsedMs: number;
}

/**
 * One card in the student explanation result.
 */
export interface StudentExplanationCard {
  /** Stable card key. */
  cardKey: string;
  /** Card title. */
  title: string;
  /** Card summary. */
  summary: string;
  /** Scannable card items. */
  items: string[];
  /** Source URI anchors used by this card. */
  sourceUris: string[];
  /** Frontend render hint such as text, formula, or source_list. */
  renderMode: string;
}

/**
 * One evidence source shown beside explanation cards.
 */
export interface StudentExplanationSource {
  /** textbook, teacher_resource, or knowledge_graph. */
  sourceType: string;
  /** Source display title. */
  title: string;
  /** Stable source URI. */
  sourceUri: string;
  /** Backend-controlled permission scope. */
  permissionScope: string;
  /** Compact evidence text. */
  snippet: string;
  /** Retrieval or match score. */
  score: number;
}

/**
 * Safe AI metadata returned by the student explanation card composer.
 */
export interface StudentExplanationAiDraft {
  /** Whether a live model call was attempted. */
  enabled: boolean;
  /** Provider that answered or was attempted. */
  providerName: string;
  /** Model that answered or was attempted. */
  modelCode: string;
  /** Provider-reported prompt tokens. */
  promptTokens: number;
  /** Provider-reported completion tokens. */
  completionTokens: number;
  /** Provider-reported total tokens. */
  totalTokens: number;
  /** Whether model output parsed into the expected card schema. */
  structured: boolean;
  /** Safe status message. */
  message: string;
  /** Retry, parse, and provider-rotation events. */
  recoveryEvents: StudentExplanationAiRecoveryEvent[];
}

/**
 * Safe AI retry and recovery event.
 */
export interface StudentExplanationAiRecoveryEvent {
  /** Event type such as MODEL_CALL_SUCCEEDED or JSON_PARSE_FAILED. */
  eventType: string;
  /** Provider involved in the event. */
  providerName: string;
  /** Model involved in the event. */
  modelCode: string;
  /** Zero-based attempt number. */
  attemptNo: number;
  /** Whether the event produced structured card output. */
  structured: boolean;
  /** Whether retry or provider rotation was still available. */
  retryable: boolean;
  /** Short safe message. */
  message: string;
}

/**
 * 单个知识点进度，用于绘制学生端动态进度条和知识图谱节点。
 */
export interface StudentKnowledgeProgress {
  /** 知识点稳定 ID。 */
  knowledgePointId?: string;
  /** 知识点显示名称。 */
  knowledgePointName: string;
  /** 教材章节或页码定位。 */
  textbookAnchor?: string;
  /** 实际可访问的飞书知识库链接；为空表示当前记录没有来源链接。 */
  feishuDocUrl?: string;
  /** 掌握百分比，范围 0 到 100。 */
  progressPercent: number;
}

/**
 * 学生薄弱点条目。
 */
export interface StudentWeakPoint {
  /** 知识点稳定 ID。 */
  knowledgePointId?: string;
  /** 知识点显示名称。 */
  knowledgePointName: string;
  /** 薄弱等级，数值越高越需要优先处理。 */
  weaknessLevel: number;
  /** 触发该薄弱点的题目或试卷证据摘要。 */
  evidenceSummary: string;
}

/**
 * 学生最近题目记录。
 */
export interface StudentRecentQuestion {
  /** 可恢复记录 ID。 */
  recordId: string;
  /** 来源类型，例如 teaching_task、uploaded_image 或 exam_paper。 */
  sourceType: string;
  /** 题目标题或摘要。 */
  questionTitle: string;
  /** 关联知识点名称。 */
  knowledgePointName: string;
  /** 当前任务状态。 */
  status: string;
}

/**
 * 学生考试趋势点。
 */
export interface StudentScorePoint {
  /** 考试名称。 */
  examName: string;
  /** 分数。 */
  score: number;
  /** 年级排名。 */
  rankInGrade: number;
  /** 从试卷分析中提取出的薄弱点数量。 */
  extractedWeakPointCount: number;
}

/**
 * 学生可访问的资源域。
 */
export interface StudentResourceScope {
  /** 权限检查使用的资源域编码。 */
  scopeCode: string;
  /** 资源域显示名称。 */
  scopeName?: string;
  /** 资源域访问策略说明。 */
  accessPolicy?: string;
}

/**
 * 教师资料源登记请求，用于飞书、本地文件夹、题库和讲义资料接入。
 */
export interface TeacherResourceRegistrationRequest {
  /** 来源类型，例如 feishu、local_path、local_docx 或 textbook_md。 */
  sourceType: string;
  /** 教师端展示标题。 */
  title: string;
  /** 飞书或外部来源 URL。 */
  originalUrl?: string;
  /** 本地文件或文件夹路径。 */
  localPath?: string;
  /** RAG 权限域，例如 TEACHER_PRIVATE、MATH_VIP 或 PUBLIC_TEXTBOOK。 */
  permissionScope: string;
  /** Native Feishu export format for Feishu resources; defaults to md. */
  feishuExportFormat?: "md" | "docx" | "pdf";
}

/**
 * 教师资料源响应，用于后台预览、删除和重建索引状态展示。
 */
export interface KnowledgePointCreateRequest {
  knowledgePointName: string;
  chapterPath: string;
  permissionScope: string;
  sourceSummary: string;
}

export interface KnowledgePointResponse {
  knowledgePointId: string;
  tenantId: string;
  ownerSubjectId: string;
  permissionScope: string;
  knowledgePointName: string;
  chapterPath: string;
  status: string;
  sourceSummary: string;
}

export interface KnowledgeRelationResponse {
  relationId: string;
  tenantId: string;
  sourceKnowledgePointId: string;
  targetKnowledgePointId: string;
  relationType: string;
  evidenceSummary?: string;
  status: string;
}

/**
 * Curated frontend graph spine assembled by the backend from trusted source files and MySQL records.
 */
export interface KnowledgeGraphSpineResponse {
  /** Curated source version, for example v0.1. */
  version: string;
  /** Backend-resolved tenant used for visibility filtering. */
  tenantId: string;
  /** Backend-resolved viewer role; never supplied by the frontend. */
  viewerRole: string;
  /** Number of visible nodes returned by the backend. */
  nodeCount: number;
  /** Number of visible directed edges returned by the backend. */
  edgeCount: number;
  /** Visible module/topic/method nodes. */
  nodes: KnowledgeGraphSpineNode[];
  /** Visible curated relations between nodes. */
  edges: KnowledgeGraphSpineEdge[];
}

/**
 * One display node in the curated high-school math spine.
 */
export interface KnowledgeGraphSpineNode {
  /** Stable knowledge point id. */
  id: string;
  /** Display label from the curated source. */
  label: string;
  /** MODULE, TOPIC, or METHOD. */
  nodeType: "MODULE" | "TOPIC" | "METHOD" | string;
  /** Chapter path or teaching method path. */
  chapterPath: string;
  /** Permission scope enforced by the backend. */
  permissionScope: string;
  /** Short source summary preserved for audit. */
  sourceSummary: string;
}

/**
 * One directed edge in the curated high-school math spine.
 */
export interface KnowledgeGraphSpineEdge {
  /** Stable relation id. */
  id: string;
  /** Source node id. */
  source: string;
  /** Target node id. */
  target: string;
  /** Relation type for display and filtering. */
  relationType: string;
  /** Short evidence summary preserved for audit. */
  evidenceSummary: string;
}

export interface QuestionBankItemCreateRequest {
  questionTitle: string;
  questionText: string;
  answerJson: string;
  difficulty: string;
  permissionScope: string;
  knowledgePointIds: string[];
}

export interface QuestionBankItemResponse {
  questionId: string;
  tenantId?: string;
  ownerSubjectId?: string;
  permissionScope?: string;
  questionTitle: string;
  questionText: string;
  answerJson?: string;
  difficulty?: string;
  status?: string;
  sourceResourceDocumentId?: string;
  sourceBlockId?: string;
  sourceChecksum?: string;
  knowledgePointIds: string[];
}

export interface TeacherBlockQuestionImportResponse {
  documentId: string;
  processedBlockCount: number;
  importedQuestionCount: number;
  skippedBlockCount: number;
  duplicateBlockCount: number;
  linkedKnowledgePointCount: number;
  importedQuestions: QuestionBankItemResponse[];
}

export interface TeacherResourceDocumentResponse {
  /** 资料源稳定 ID。 */
  documentId: string;
  /** 租户 ID。 */
  tenantId?: string;
  /** 资料所属教师或管理员主体 ID。 */
  ownerSubjectId?: string;
  /** 来源类型。 */
  sourceType?: string;
  /** 教师端展示标题。 */
  title: string;
  /** 飞书或外部来源 URL。 */
  originalUrl?: string;
  /** 本地文件或文件夹路径。 */
  localPath?: string;
  /** RAG 权限域。 */
  permissionScope?: string;
  /** 同步状态。 */
  syncStatus: string;
  /** 解析状态。 */
  parseStatus?: string;
  /** embedding 状态。 */
  embeddingStatus?: string;
  /** BM25/Milvus 索引状态。 */
  indexStatus?: string;
  /** Native Feishu export format selected for Feishu resources. */
  feishuExportFormat?: "md" | "docx" | "pdf";
  /** 本地预览文件列表。 */
  previewFiles?: TeacherResourcePreviewFile[];
}

/**
 * 教师资料源本地文件预览项。
 */
export interface TeacherResourcePreviewFile {
  /** 文件名。 */
  fileName: string;
  /** 相对登记根目录的路径。 */
  relativePath: string;
  /** 文件大小，单位字节。 */
  fileSizeBytes: number;
}

export interface TeacherResourceBlockSearchResponse {
  queryId: string;
  query: string;
  limit: number;
  retrievalMode: string;
  hitCount: number;
  hits: TeacherResourceBlockSearchHit[];
}

export interface TeacherResourceBlockSearchHit {
  documentId: string;
  documentTitle: string;
  permissionScope: string;
  blockId: string;
  blockType: string;
  blockOrder: number;
  chapter?: string;
  section?: string;
  pageNo?: number | null;
  snippet: string;
  score: number;
}

export interface TeacherResourceBlockSearchAuditEvent {
  queryId: string;
  tenantId: string;
  subjectType: string;
  subjectId: string;
  query: string;
  limit: number;
  retrievalMode: string;
  hitCount: number;
  elapsedMs: number;
  endpoint: string;
  hits: TeacherResourceBlockSearchAuditHit[];
}

export interface TeacherResourceBlockSearchAuditHit {
  documentId: string;
  documentTitle: string;
  permissionScope: string;
  blockId: string;
  blockType: string;
  blockOrder: number;
  pageNo?: number | null;
  score: number;
}

export interface TeacherFeishuDiscoveryRequest {
  mode: "list" | "search";
  query?: string;
  rootUrl?: string;
  listDepth?: number;
  maxDepth?: number;
}

export interface TeacherFeishuDiscoveryResponse {
  queryId: string;
  mode: string;
  rootUrl: string;
  keyword?: string;
  depth: number;
  candidateCount: number;
  candidates: TeacherFeishuDiscoveryCandidate[];
  status: string;
  message: string;
}

export interface TeacherFeishuDiscoveryCandidate {
  resourceType: string;
  token: string;
  name: string;
  path: string;
  url: string;
  depth: number;
  downloadable: boolean;
}

export interface TeacherSourceSyncJobResponse {
  jobId: string;
  documentId: string;
  tenantId?: string;
  sourceType?: string;
  operation: string;
  status: string;
  phase: string;
  attempt?: number;
  createdBy?: string;
  stagingPath?: string;
  message?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface TeacherSourceSyncCheckpointResponse {
  /** Source synchronization job id. */
  jobId: string;
  /** Backend tenant that owns the checkpoint. */
  tenantId: string;
  /** Source document id bound to the checkpoint. */
  documentId: string;
  /** Feishu root token being traversed. */
  rootToken: string;
  /** Current folder token where traversal stopped. */
  currentFolderToken: string;
  /** Human-readable folder path for progress display. */
  currentPath: string;
  /** Provider pagination cursor when the current folder has more pages. */
  pageToken?: string | null;
  /** JSON array of visited folder tokens. */
  visitedFolderTokensJson: string;
  /** JSON array of successfully downloaded item descriptors. */
  downloadedItemsJson: string;
  /** JSON array of failed item descriptors. */
  failedItemsJson: string;
  /** Checkpoint schema version. */
  cursorVersion: number;
  /** Backend update timestamp. */
  updatedAt: string;
}

export interface VectorIndexStatusResponse {
  enabled: boolean;
  configured: boolean;
  collectionName: string;
  dimension: number;
  embeddingModel: string;
  milvusUri: string;
  collectionState: string;
  indexState: string;
  loadState: string;
  rowCount: number;
  status: string;
}

export interface VectorIndexRebuildResponse {
  status: string;
  documentId: string;
  collectionName: string;
  blockCount: number;
  embeddedCount: number;
  upsertedCount: number;
  embeddingModel: string;
  promptTokens: number;
  message: string;
}

export interface SystemRuntimeStatusResponse {
  deployment: {
    ready: boolean;
    mode: string;
    blockingIssues: string[];
    warnings: string[];
  };
  ai: {
    defaultProviderName: string;
    defaultModelCode: string;
    defaultProviderConfigured: boolean;
    enabledProviderCount: number;
    providers: Array<{
      providerName: string;
      modelCode: string;
      configured: boolean;
      baseUrlConfigured: boolean;
      apiKeyConfigured: boolean;
      modelConfigured: boolean;
    }>;
  };
  auth: {
    persistentStoreRequired: boolean;
    mode: string;
  };
  database: {
    enabled: boolean;
    configured: boolean;
    urlConfigured: boolean;
    usernameConfigured: boolean;
    studentExplanationHistoryDurable: boolean;
    migrationRunnerEnabled: boolean;
    migrationLocation: string;
    mode: string;
  };
  redis: {
    redissonEnabled: boolean;
    redissonAddress: string;
    rateLimitEnabled: boolean;
    rateLimitKeyPrefix: string;
    capabilityStoreEnabled: boolean;
    capabilityStoreKeyPrefix: string;
    searchCacheEnabled: boolean;
    searchCacheKeyPrefix: string;
    searchCacheTtl: string;
  };
  vectorIndex: {
    enabled: boolean;
    configured: boolean;
    collectionName: string;
    dimension: number;
    embeddingModel: string;
    milvusUri: string;
    collectionState: string;
    indexState: string;
    loadState: string;
    rowCount: number;
    status: string;
  };
  feishu: {
    processDownloaderEnabled: boolean;
    downloaderScriptConfigured: boolean;
    downloaderScriptExists: boolean;
    appkeyPathConfigured: boolean;
    appkeyFileExists: boolean;
    stagingRootConfigured: boolean;
    stagingRootExistsOrCreatable: boolean;
    defaultUrlHost: string;
    smokeMaxFiles: number;
    processTimeoutSeconds: number;
    mode: string;
  };
}

type FetchLike = (
  input: string,
  init?: RequestInit,
) => Promise<Pick<Response, "ok" | "status" | "json" | "text" | "arrayBuffer" | "headers">>;

const AUTH_STORAGE_KEY = "math-agent:auth-session";
const DEVICE_ID_HEADER = { "X-Device-Id": "local-browser-console" };

export function createTextbookApiClient(baseUrl: string, fetchImpl: FetchLike = fetch) {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, "");

  /**
   * 请求后端 JSON。身份只通过后端登录 token 传递，不能使用前端自报角色或学生 ID。
   */
    /** 生成 UUID v4，优先使用 Crypto API，回退到手动实现。用于幂等 clientRequestId。 */
  function generateUUID(): string {
    try {
      return globalThis.crypto.randomUUID();
    } catch {
      return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
      });
    }
  }

  /**
   * Turns structured backend errors into concise UI text; raw JSON makes operational pages hard to read.
   */
  function backendErrorMessage(status: number, body: string): string {
    const trimmed = body.trim();
    if (trimmed.startsWith("{")) {
      try {
        const parsed = JSON.parse(trimmed) as { message?: unknown; code?: unknown; error?: unknown };
        const message = typeof parsed.message === "string" ? parsed.message : "";
        const code = typeof parsed.code === "string" ? parsed.code : "";
        if (message && code) {
          return `Backend request failed: ${status} ${message} (${code})`;
        }
        if (message) {
          return `Backend request failed: ${status} ${message}`;
        }
        if (typeof parsed.error === "string") {
          return `Backend request failed: ${status} ${parsed.error}`;
        }
      } catch {
        // Fall back to the raw text when the backend returns malformed JSON.
      }
    }
    return `Backend request failed: ${status} ${trimmed}`.trim();
  }

  async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      credentials: init.credentials ?? "include",
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(backendErrorMessage(response.status, body));
    }
    return response.json() as Promise<T>;
  }

  /**
   * Requests a backend text response while preserving the same session and device headers.
   */
  /** 合并两个 AbortSignal，任一触发中止则整体中止。用于同时支持外部 signal 和内部超时。 */
  function combineSignals(s1: AbortSignal, s2: AbortSignal): AbortSignal {
    const controller = new AbortController();
    const onAbort = () => controller.abort();
    s1.addEventListener('abort', onAbort);
    s2.addEventListener('abort', onAbort);
    if (s1.aborted || s2.aborted) controller.abort();
    return controller.signal;
  }

  async function requestText(path: string, init: RequestInit = {}): Promise<string> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      credentials: init.credentials ?? "include",
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(backendErrorMessage(response.status, body));
    }
    return response.text();
  }

  /**
   * Requests backend binary content while preserving the same session and device headers.
   */
  async function requestBytes(path: string, init: RequestInit = {}): Promise<Uint8Array> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      credentials: init.credentials ?? "include",
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(backendErrorMessage(response.status, body));
    }
    return new Uint8Array(await response.arrayBuffer());
  }

  async function requestBytesWithHeaders(path: string, init: RequestInit = {}): Promise<{
    bytes: Uint8Array;
    headers: Headers;
  }> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
  const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      credentials: init.credentials ?? "include",
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(backendErrorMessage(response.status, body));
    }
    return {
      bytes: new Uint8Array(await response.arrayBuffer()),
      headers: response.headers as Headers,
    };
  }

  /**
   * Uploads multipart form data while preserving backend session headers and browser-generated boundaries.
   */
  async function requestFormJson<T>(path: string, formData: FormData, init: RequestInit = {}): Promise<T> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      method: init.method ?? "POST",
      credentials: init.credentials ?? "include",
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
      body: formData,
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(backendErrorMessage(response.status, body));
    }
    return response.json() as Promise<T>;
  }

  /**
   * Applies for a one-time capability token bound to the exact consuming request body.
   */
  async function applyCapability(
    action: string,
    path: string,
    body: string,
    idempotencyKey: string,
    maxCost = 1,
  ): Promise<CapabilityTokenResponse> {
    const requestHash = await hashRequestBody(body);
    const capability = await requestJson<CapabilityTokenResponse>("/api/security/capabilities", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action,
        path,
        requestHash,
        idempotencyKey,
        maxCost,
      }),
    });
    return { ...capability, requestHash };
  }

  /**
   * Sends one standard MCP JSON-RPC request to an absolute MCP URL without platform session headers.
   */
  async function requestMcpJsonRpc(url: string, secretKey: string, body: unknown): Promise<{
    protocolVersion: string;
    body: Record<string, unknown>;
  }> {
    const response = await fetchImpl(url, {
      method: "POST",
      headers: {
        Accept: "application/json, text/event-stream",
        "Content-Type": "application/json",
        "MCP-Protocol-Version": "2025-11-25",
        Authorization: `Bearer ${secretKey}`,
      },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`MCP request failed: ${response.status} ${text}`.trim());
    }
    const parsed = text ? JSON.parse(text) as Record<string, unknown> : {};
    if (parsed.error) {
      throw new Error(`MCP JSON-RPC error: ${JSON.stringify(parsed.error)}`);
    }
    return {
      protocolVersion: response.headers.get("MCP-Protocol-Version") ?? "unknown",
      body: parsed,
    };
  }

  return {
    /**
     * 登录并保存后端会话 token；后续请求只携带 token，不携带 userId/role/studentId。
     */
    async login(request: LoginRequest): Promise<LoginResponse> {
      const response = await requestJson<LoginResponse>("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
      globalThis.localStorage?.setItem(AUTH_STORAGE_KEY, JSON.stringify(response));
      return response;
    },

    /**
     * Registers a student account and stores the backend-issued session token.
     */
    async register(request: RegisterRequest): Promise<LoginResponse> {
      const response = await requestJson<LoginResponse>("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
      globalThis.localStorage?.setItem(AUTH_STORAGE_KEY, JSON.stringify(response));
      return response;
    },

    /**
     * 读取教材资源摘要。
     */
    async currentSession(): Promise<LoginResponse> {
      const response = await requestJson<LoginResponse>("/api/auth/session");
      globalThis.localStorage?.setItem(AUTH_STORAGE_KEY, JSON.stringify(response));
      return response;
    },

    getSummary(): Promise<TextbookSummary> {
      return requestJson<TextbookSummary>("/api/resources/textbooks/summary");
    },

    /**
     * 执行教材证据检索。
     */
    search(query: string, limit: number): Promise<TextbookSearchResponse> {
      const params = new URLSearchParams({
        query,
        limit: String(limit),
      });
      return requestJson<TextbookSearchResponse>(`/api/retrieval/textbooks/search?${params.toString()}`);
    },

    /**
     * 按 queryId 读取检索审计详情。
     */
    getAudit(queryId: string): Promise<RetrievalAuditDetail> {
      return requestJson<RetrievalAuditDetail>(`/api/retrieval/audit/${encodeURIComponent(queryId)}`);
    },

    /**
     * Reads high-value capability audit rows for teacher/admin security review.
     */
    listCapabilityAudits(query: CapabilityAuditQuery = {}): Promise<CapabilityAuditLogResponse[]> {
      const params = new URLSearchParams();
      if (query.subjectType) {
        params.set("subjectType", query.subjectType);
      }
      if (query.subjectId) {
        params.set("subjectId", query.subjectId);
      }
      if (query.action) {
        params.set("action", query.action);
      }
      if (query.decision) {
        params.set("decision", query.decision);
      }
      if (query.limit) {
        params.set("limit", String(query.limit));
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      return requestJson<CapabilityAuditLogResponse[]>(`/api/security/capability-audits${suffix}`);
    },

    /**
     * 提交可恢复教学任务。前端需要保存返回的 taskId，页面离开后可继续查询。
     */
    async submitTeachingTask(request: TeachingTaskRequest): Promise<TeachingTaskResponse> {
      const body = JSON.stringify(request);
      const capability = await applyCapability(
        "teaching:submit",
        "/api/teaching/tasks",
        body,
        request.clientRequestId,
        Math.max(0, request.evidenceLimit),
      );
      return requestJson<TeachingTaskResponse>("/api/teaching/tasks", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    /**
     * 按 taskId 读取教学任务结果，用于页面恢复和轮询。
     */
    /**
     * Stores a student memory entry after applying a capability token for the exact request body.
     */
    async rememberStudentMemory(request: StudentMemoryRequest): Promise<StudentMemoryResponse> {
      const body = JSON.stringify(request);
      const capability = await applyCapability(
        "student-memory:remember",
        "/api/students/memory/remember",
        body,
        `student-memory-remember:${request.knowledgePointName ?? "general"}:${request.questionText}`,
      );
      return requestJson<StudentMemoryResponse>("/api/students/memory/remember", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    getTeachingTask(taskId: string): Promise<TeachingTaskResponse> {
      return requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodeURIComponent(taskId)}`);
    },

    listTeachingTasks(limit = 20): Promise<TeachingTaskResponse[]> {
      return requestJson<TeachingTaskResponse[]>(`/api/teaching/tasks?limit=${encodeURIComponent(String(limit))}`);
    },
    listTeachingHandoutTemplates(): Promise<TeachingHandoutTemplateResponse[]> {
      return requestJson<TeachingHandoutTemplateResponse[]>("/api/teaching/handout-templates");
    },

    getTeachingHandoutTemplatePreviewImage(templateCode: string): Promise<Uint8Array> {
      return requestBytes(`/api/teaching/handout-templates/${encodeURIComponent(templateCode)}/preview.png`);
    },

    getTeachingHandoutTemplateReferencePdf(templateCode: string): Promise<Uint8Array> {
      return requestBytes(`/api/teaching/handout-templates/${encodeURIComponent(templateCode)}/reference.pdf`);
    },

    /**
     * 读取学生学习画像。默认使用本地学生身份，避免学生面板误带教师权限。
     */
    /**
     * Downloads the LaTeX handout for a teaching task after applying a one-time capability token.
     */
    async exportTeachingTaskLatex(taskId: string, version: "teacher" | "student" = "teacher"): Promise<string> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/latex`;
      const capability = await applyCapability(
        "teaching-handout:export-latex",
        path,
        "",
        `teaching-handout-export-latex:${taskId}:${version}`,
      );
      return requestText(path, {
        method: "GET",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    /**
     * Loads LaTeX handout source for inline frontend preview with a separate capability audit action.
     */
    async previewTeachingTaskLatex(taskId: string, version: "teacher" | "student" = "teacher"): Promise<string> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/latex/preview`;
      const capability = await applyCapability(
        "teaching-handout:preview-latex",
        path,
        "",
        `teaching-handout-preview-latex:${taskId}:${version}`,
      );
      return requestText(path, {
        method: "GET",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    /**
     * Downloads the PDF handout for a teaching task after applying a one-time capability token.
     */
    async exportTeachingTaskPdf(taskId: string, version: "teacher" | "student" = "teacher"): Promise<TeachingHandoutPdfResponse> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/pdf`;
      const capability = await applyCapability(
        "teaching-handout:export-pdf",
        path,
        "",
        `teaching-handout-export-pdf:${taskId}:${version}`,
        2,
      );
      const response = await requestBytesWithHeaders(path, {
        method: "GET",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
      return {
        bytes: response.bytes,
        renderer: response.headers.get("X-Handout-Renderer") ?? "",
        pageCount: Number(response.headers.get("X-Handout-Page-Count") ?? "0") || 0,
      };
    },

    /**
     * Loads the PDF handout for inline frontend preview after applying a preview-specific capability token.
     */
    async previewTeachingTaskPdf(taskId: string, version: "teacher" | "student" = "teacher"): Promise<TeachingHandoutPdfResponse> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/pdf/preview`;
      const capability = await applyCapability(
        "teaching-handout:preview-pdf",
        path,
        "",
        `teaching-handout-preview-pdf:${taskId}:${version}`,
        2,
      );
      const response = await requestBytesWithHeaders(path, {
        method: "GET",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
      return {
        bytes: response.bytes,
        renderer: response.headers.get("X-Handout-Renderer") ?? "",
        pageCount: Number(response.headers.get("X-Handout-Page-Count") ?? "0") || 0,
      };
    },

    /**
     * Creates a short-lived backend ZIP package for selected handouts and folder grouping.
     */
    async createTeachingHandoutBatchZip(
      request: TeachingHandoutBatchExportRequest,
    ): Promise<TeachingHandoutBatchExportResponse> {
      const body = JSON.stringify(request);
      const path = "/api/teaching/handouts/batch/zip";
      const idempotencyKey = `teaching-handout-batch-export-zip:${(request.folderIds ?? []).join(",")}:${(
        request.folderPaths ?? []
      ).join(",")}:${request.taskIds.join(",")}`;
      const capability = await applyCapability(
        "teaching-handout:batch-export-zip",
        path,
        body,
        idempotencyKey,
        Math.max(1, request.taskIds.length),
      );
      return requestJson<TeachingHandoutBatchExportResponse>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    /**
     * Downloads a temporary handout ZIP after applying a download-specific capability token.
     */
    async downloadTeachingHandoutBatchZip(batchId: string): Promise<Uint8Array> {
      const path = `/api/teaching/handouts/batch/zip/${encodeURIComponent(batchId)}/download`;
      const capability = await applyCapability(
        "teaching-handout:batch-download-zip",
        path,
        "",
        `teaching-handout-batch-download-zip:${batchId}`,
        2,
      );
      return requestBytes(path, {
        method: "GET",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    /**
     * Submits human feedback for an owned teaching task after applying a one-time capability token.
     */
    async submitTeachingHumanFeedback(
      taskId: string,
      request: TeachingHumanFeedbackRequest,
    ): Promise<TeachingHumanFeedbackResponse> {
      const body = JSON.stringify(request);
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/feedback`;
      const capability = await applyCapability(
        "teaching-feedback:submit",
        path,
        body,
        `teaching-feedback-submit:${taskId}:${request.decision}`,
      );
      return requestJson<TeachingHumanFeedbackResponse>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    /**
     * Lists human review records for the current backend-owned teaching task.
     */
    listTeachingHumanFeedback(taskId: string): Promise<TeachingHumanFeedbackResponse[]> {
      return requestJson<TeachingHumanFeedbackResponse[]>(
        `/api/teaching/tasks/${encodeURIComponent(taskId)}/feedback`,
      );
    },

    /**
     * Plans an AI agent run using backend session identity and server-side policy.
     */
    planAgentRun(request: AgentRunPlanRequest): Promise<AgentRunPlanResponse> {
      return requestJson<AgentRunPlanResponse>("/api/agents/run-plan", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    getAgentModelCatalog(): Promise<AgentModelCatalogResponse> {
      return requestJson<AgentModelCatalogResponse>("/api/agents/model-catalog");
    },

    getAgentModelHealth(): Promise<AgentModelHealthResponse> {
      return requestJson<AgentModelHealthResponse>("/api/agents/model-health");
    },

    getSystemRuntimeStatus(): Promise<SystemRuntimeStatusResponse> {
      return requestJson<SystemRuntimeStatusResponse>("/api/system/runtime");
    },

    getVectorIndexStatus(): Promise<VectorIndexStatusResponse> {
      return requestJson<VectorIndexStatusResponse>("/api/vector-index/status");
    },

    /**
     * Executes a planned AI agent run. High-value runs first acquire a one-time capability token.
     */
    async executeAgentRun(request: AgentRunExecuteRequest): Promise<AgentRunExecuteResponse> {
      const body = JSON.stringify(request);
      const path = "/api/agents/execute";
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (request.plan.capabilityRequired) {
        const capability = await applyCapability(
          request.plan.capabilityAction || `agent-run:${request.plan.agentCode}`,
          path,
          body,
          `agent-run:${request.plan.planId}`,
          Math.max(1, Math.ceil(request.plan.estimatedCost)),
        );
        headers["X-Capability-Token"] = capability.token;
        headers["X-Request-Hash"] = capability.requestHash;
      }
      return requestJson<AgentRunExecuteResponse>(path, {
        method: "POST",
        headers,
        body,
      });
    },

    /**
     * Runs protected multi-agent writing after acquiring a one-time capability token.
     */
    async runMultiAgentWriting(request: MultiAgentWritingRequest): Promise<MultiAgentWritingResponse> {
      const body = JSON.stringify(request);
      const path = "/api/agents/writing/courseware";
      const capability = await applyCapability(
        "agent-run:CoursewareAgent",
        path,
        body,
        `multi-agent-writing:${request.writingGoal}:${request.questionText}`,
        3,
      );
      return requestJson<MultiAgentWritingResponse>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    /**
     * Starts multi-agent writing in the background and returns a workflow id for polling.
     */
    async startAsyncMultiAgentWriting(request: MultiAgentWritingRequest): Promise<MultiAgentWritingResponse> {
      const body = JSON.stringify(request);
      const path = "/api/agents/writing/courseware/async";
      const capability = await applyCapability(
        "agent-run:CoursewareAgent",
        path,
        body,
        `multi-agent-writing-async:${request.writingGoal}:${request.questionText}`,
        3,
      );
      return requestJson<MultiAgentWritingResponse>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    /**
     * Resumes a failed multi-agent writing workflow from the first missing stage.
     */
    async resumeMultiAgentWriting(
      workflowId: string,
      request: MultiAgentWritingRequest,
    ): Promise<MultiAgentWritingResponse> {
      const body = JSON.stringify(request);
      const encodedWorkflowId = encodeURIComponent(workflowId);
      const path = `/api/agents/writing/${encodedWorkflowId}/resume`;
      const capability = await applyCapability(
        "agent-run:CoursewareAgent",
        path,
        body,
        `multi-agent-writing-resume:${workflowId}:${request.writingGoal}:${request.questionText}`,
        3,
      );
      return requestJson<MultiAgentWritingResponse>(path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    /**
     * Reads the latest safe multi-agent writing workflow status by workflow id.
     */
    getMultiAgentWritingWorkflow(workflowId: string): Promise<MultiAgentWritingResponse> {
      return requestJson<MultiAgentWritingResponse>(`/api/agents/writing/${encodeURIComponent(workflowId)}`);
    },

    /**
     * Reads safe ordered traces for a multi-agent writing workflow.
     */
    getMultiAgentWritingTraces(workflowId: string): Promise<MultiAgentWritingTraceResponse> {
      return requestJson<MultiAgentWritingTraceResponse>(
        `/api/agents/writing/${encodeURIComponent(workflowId)}/traces`,
      );
    },

    /**
     * Reads owner-visible generated content for review and frontend preview.
     */
    getMultiAgentWritingArtifact(workflowId: string): Promise<MultiAgentWritingArtifact> {
      return requestJson<MultiAgentWritingArtifact>(
        `/api/agents/writing/${encodeURIComponent(workflowId)}/artifact`,
      );
    },

    /**
     * Exports generated writing content as Markdown, LaTeX, or ZIP.
     */
    exportMultiAgentWritingArtifact(
      workflowId: string,
      format: "markdown" | "latex" | "pdf" | "zip",
    ): Promise<MultiAgentWritingArtifactExportResponse> {
      const params = new URLSearchParams({ format });
      return requestJson<MultiAgentWritingArtifactExportResponse>(
        `/api/agents/writing/${encodeURIComponent(workflowId)}/artifact/export?${params}`,
      );
    },

    /**
     * Lists agent traces visible to the backend session subject.
     */
    listAgentTraces(query: AgentTraceQuery = {}): Promise<AgentTraceResponse[]> {
      const params = new URLSearchParams();
      if (query.agentCode) {
        params.set("agentCode", query.agentCode);
      }
      if (query.status) {
        params.set("status", query.status);
      }
      if (query.planId) {
        params.set("planId", query.planId);
      }
      if (query.planIdPrefix) {
        params.set("planIdPrefix", query.planIdPrefix);
      }
      if (query.limit) {
        params.set("limit", String(query.limit));
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      return requestJson<AgentTraceResponse[]>(`/api/agents/traces${suffix}`);
    },

    /**
     * Summarizes provider-reported usage for traces visible to the backend session subject.
     */
    getAgentTraceUsageSummary(query: AgentTraceQuery = {}): Promise<AgentTraceUsageSummaryResponse> {
      const params = new URLSearchParams();
      if (query.agentCode) {
        params.set("agentCode", query.agentCode);
      }
      if (query.status) {
        params.set("status", query.status);
      }
      if (query.planId) {
        params.set("planId", query.planId);
      }
      if (query.planIdPrefix) {
        params.set("planIdPrefix", query.planIdPrefix);
      }
      if (query.limit) {
        params.set("limit", String(query.limit));
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      return requestJson<AgentTraceUsageSummaryResponse>(`/api/agents/traces/usage-summary${suffix}`);
    },

    /**
     * Summarizes retry/fallback/parse diagnostics for traces visible to the backend session subject.
     */
    getAgentTraceDiagnosticSummary(query: AgentTraceQuery = {}): Promise<AgentTraceDiagnosticSummaryResponse> {
      const params = new URLSearchParams();
      if (query.agentCode) {
        params.set("agentCode", query.agentCode);
      }
      if (query.status) {
        params.set("status", query.status);
      }
      if (query.planId) {
        params.set("planId", query.planId);
      }
      if (query.planIdPrefix) {
        params.set("planIdPrefix", query.planIdPrefix);
      }
      if (query.limit) {
        params.set("limit", String(query.limit));
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      return requestJson<AgentTraceDiagnosticSummaryResponse>(`/api/agents/traces/diagnostic-summary${suffix}`);
    },

    listMcpKeys(): Promise<McpClientKeyResponse[]> {
      return requestJson<McpClientKeyResponse[]>("/api/mcp/keys");
    },

    createMcpKey(): Promise<McpClientKeyCreatedResponse> {
      return requestJson<McpClientKeyCreatedResponse>("/api/mcp/keys", {
        method: "POST",
      });
    },

    revokeMcpKey(keyId: string): Promise<McpClientKeyRevocationResponse> {
      return requestJson<McpClientKeyRevocationResponse>(`/api/mcp/keys/${encodeURIComponent(keyId)}/revoke`, {
        method: "POST",
      });
    },

    getMyMcpConfiguration(): Promise<McpConfigurationResponse> {
      return requestJson<McpConfigurationResponse>("/api/mcp/configuration/me");
    },

    /**
     * Runs a real standard MCP connection smoke test against the configured URL and Bearer secret.
     */
    async testMcpConnection(url: string, secretKey: string): Promise<McpConnectionTestResult> {
      const normalizedUrl = url.trim().replace(/\/+$/, "");
      const normalizedSecret = secretKey.trim();
      if (!normalizedUrl) {
        throw new Error("MCP URL is required");
      }
      if (!normalizedSecret) {
        throw new Error("MCP secretKey is required");
      }
      const initialize = await requestMcpJsonRpc(normalizedUrl, normalizedSecret, {
        jsonrpc: "2.0",
        id: "frontend-init",
        method: "initialize",
        params: {
          protocolVersion: "2025-11-25",
          capabilities: {},
          clientInfo: { name: "math-agent-frontend", version: "0.1.0" },
        },
      });
      const initializeResult = (initialize.body.result ?? {}) as Record<string, unknown>;
      const serverInfo = (initializeResult.serverInfo ?? {}) as Record<string, unknown>;
      const toolsList = await requestMcpJsonRpc(normalizedUrl, normalizedSecret, {
        jsonrpc: "2.0",
        id: "frontend-tools",
        method: "tools/list",
        params: {},
      });
      const toolsResult = (toolsList.body.result ?? {}) as Record<string, unknown>;
      const tools = Array.isArray(toolsResult.tools)
        ? toolsResult.tools
            .map((tool) => ((tool as Record<string, unknown>).name ?? "").toString())
            .filter(Boolean)
        : [];
      return {
        url: normalizedUrl,
        protocolVersion: toolsList.protocolVersion || initialize.protocolVersion,
        serverName: (serverInfo.name ?? "unknown").toString(),
        serverVersion: (serverInfo.version ?? "unknown").toString(),
        tools,
        toolCount: tools.length,
      };
    },

    getStudentDashboard(studentId?: string): Promise<StudentDashboardResponse> {
      const params = new URLSearchParams();
      if (studentId) {
        params.set("studentId", studentId);
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      return requestJson<StudentDashboardResponse>(`/api/students/dashboard${suffix}`);
    },

    explainStudentQuestion(request: StudentExplanationRequest): Promise<StudentExplanationResponse> {
      return requestJson<StudentExplanationResponse>("/api/students/explanations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    uploadStudentExplanationImage(file: File): Promise<StudentExplanationImageUploadResponse> {
      const formData = new FormData();
      formData.append("file", file);
      return requestFormJson<StudentExplanationImageUploadResponse>("/api/students/explanations/images", formData);
    },

    getStudentExplanationHistory(conversationId?: string, limit = 20): Promise<StudentExplanationHistoryResponse> {
      const params = new URLSearchParams();
      if (conversationId) {
        params.set("conversationId", conversationId);
      }
      params.set("limit", String(limit));
      return requestJson<StudentExplanationHistoryResponse>(`/api/students/explanations/history?${params.toString()}`);
    },

    async refreshStudentDashboard(studentId?: string): Promise<StudentDashboardResponse> {
      const params = new URLSearchParams();
      if (studentId) {
        params.set("studentId", studentId);
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      const path = `/api/students/dashboard/refresh${suffix}`;
      const capability = await applyCapability(
        "student-dashboard:refresh",
        "/api/students/dashboard/refresh",
        "",
        `student-dashboard-refresh:${studentId ?? "self"}`,
      );
      return requestJson<StudentDashboardResponse>(path, {
        method: "POST",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    listKnowledgePoints(): Promise<KnowledgePointResponse[]> {
      return requestJson<KnowledgePointResponse[]>("/api/knowledge/points");
    },

    listKnowledgeRelations(): Promise<KnowledgeRelationResponse[]> {
      return requestJson<KnowledgeRelationResponse[]>("/api/knowledge/relations");
    },

    getKnowledgeGraphSpine(): Promise<KnowledgeGraphSpineResponse> {
      return requestJson<KnowledgeGraphSpineResponse>("/api/knowledge/graph/spine");
    },

    async createKnowledgePoint(request: KnowledgePointCreateRequest): Promise<KnowledgePointResponse> {
      const body = JSON.stringify(request);
      const capability = await applyCapability(
        "knowledge-point:create",
        "/api/knowledge/points",
        body,
        `knowledge-point-create:${request.knowledgePointName}`,
      );
      return requestJson<KnowledgePointResponse>("/api/knowledge/points", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    async createQuestionBankItem(request: QuestionBankItemCreateRequest): Promise<QuestionBankItemResponse> {
      const body = JSON.stringify(request);
      const capability = await applyCapability(
        "question-bank:create",
        "/api/question-bank/items",
        body,
        `question-bank-create:${request.questionTitle}`,
      );
      return requestJson<QuestionBankItemResponse>("/api/question-bank/items", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
        body,
      });
    },

    searchQuestionBankItems(query: string, limit = 10): Promise<QuestionBankItemResponse[]> {
      const params = new URLSearchParams({
        query,
        limit: String(limit),
      });
      return requestJson<QuestionBankItemResponse[]>(`/api/question-bank/items?${params.toString()}`);
    },

    async importTeacherResourceQuestions(documentId: string): Promise<TeacherBlockQuestionImportResponse> {
      const encodedDocumentId = encodeURIComponent(documentId);
      const path = `/api/question-bank/import/teacher-resources/${encodedDocumentId}`;
      const capability = await applyCapability(
        "question-bank:import-teacher-resource",
        path,
        "",
        `question-bank-import-teacher-resource:${documentId}`,
        1,
      );
      return requestJson<TeacherBlockQuestionImportResponse>(path, {
        method: "POST",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    /**
     * 读取当前教师可见的资料源列表。
     */
    listTeacherResources(): Promise<TeacherResourceDocumentResponse[]> {
      return requestJson<TeacherResourceDocumentResponse[]>("/api/teacher/resources");
    },

    searchTeacherResourceBlocks(query: string, limit = 10): Promise<TeacherResourceBlockSearchResponse> {
      const path = `/api/teacher/resources/search?query=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`;
      return requestJson<TeacherResourceBlockSearchResponse>(path);
    },

    getTeacherResourceBlockSearchAudit(queryId: string): Promise<TeacherResourceBlockSearchAuditEvent> {
      return requestJson<TeacherResourceBlockSearchAuditEvent>(
        `/api/teacher/resources/search/audit/${encodeURIComponent(queryId)}`,
      );
    },

    discoverFeishuResources(request: TeacherFeishuDiscoveryRequest): Promise<TeacherFeishuDiscoveryResponse> {
      const path = [
        `/api/teacher/resources/feishu/discovery?mode=${encodeURIComponent(request.mode)}`,
        `query=${encodeURIComponent(request.query ?? "")}`,
        `rootUrl=${encodeURIComponent(request.rootUrl ?? "")}`,
        `listDepth=${encodeURIComponent(String(request.listDepth ?? 1))}`,
        `maxDepth=${encodeURIComponent(String(request.maxDepth ?? 5))}`,
      ].join("&");
      return requestJson<TeacherFeishuDiscoveryResponse>(path);
    },

    /**
     * 登记教师资料源，后端会返回预览和等待重建索引状态。
     */
    registerTeacherResource(
      request: TeacherResourceRegistrationRequest,
    ): Promise<TeacherResourceDocumentResponse> {
      const normalizedRequest = request.sourceType === "feishu"
        ? { ...request, feishuExportFormat: request.feishuExportFormat ?? "md" }
        : request;
      const body = JSON.stringify(normalizedRequest);
      return applyCapability(
        "teacher-resource:register",
        "/api/teacher/resources",
        body,
        `teacher-resource-register:${normalizedRequest.sourceType}:${normalizedRequest.title}`,
      ).then((capability) =>
        requestJson<TeacherResourceDocumentResponse>("/api/teacher/resources", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Capability-Token": capability.token,
            "X-Request-Hash": capability.requestHash,
          },
          body,
        }),
      );
    },

    /**
     * 归档教师资料源，避免硬删除导致旧讲解引用断裂。
     */
    async archiveTeacherResource(documentId: string): Promise<TeacherResourceDocumentResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}`;
      const capability = await applyCapability(
        "teacher-resource:archive",
        path,
        "",
        `teacher-resource-archive:${documentId}`,
      );
      return requestJson<TeacherResourceDocumentResponse>(path, {
        method: "DELETE",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    listTeacherResourceSyncJobs(documentId: string): Promise<TeacherSourceSyncJobResponse[]> {
      return requestJson<TeacherSourceSyncJobResponse[]>(
        `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs`,
      );
    },

    getTeacherResourceSyncCheckpoint(
      documentId: string,
      jobId: string,
    ): Promise<TeacherSourceSyncCheckpointResponse | null> {
      return requestJson<TeacherSourceSyncCheckpointResponse | null>(
        `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs/${encodeURIComponent(jobId)}/checkpoint`,
      );
    },

    async createTeacherResourceSyncJob(documentId: string): Promise<TeacherSourceSyncJobResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs`;
      const capability = await applyCapability(
        "teacher-resource:sync",
        path,
        "",
        `teacher-resource-sync:${documentId}`,
      );
      return requestJson<TeacherSourceSyncJobResponse>(path, {
        method: "POST",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    async executeTeacherResourceSyncJob(
      documentId: string,
      jobId: string,
    ): Promise<TeacherSourceSyncJobResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs/${encodeURIComponent(jobId)}/execute`;
      const capability = await applyCapability(
        "teacher-resource:sync-execute",
        path,
        "",
        `teacher-resource-sync-execute:${documentId}:${jobId}`,
        2,
      );
      return requestJson<TeacherSourceSyncJobResponse>(path, {
        method: "POST",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    async resumeTeacherResourceSyncJob(
      documentId: string,
      jobId: string,
    ): Promise<TeacherSourceSyncJobResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs/${encodeURIComponent(jobId)}/resume`;
      const capability = await applyCapability(
        "teacher-resource:sync-resume",
        path,
        "",
        `teacher-resource-sync-resume:${documentId}:${jobId}`,
        2,
      );
      return requestJson<TeacherSourceSyncJobResponse>(path, {
        method: "POST",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },

    async rebuildTeacherResourceVectorIndex(documentId: string): Promise<VectorIndexRebuildResponse> {
      const path = `/api/vector-index/teacher-resources/${encodeURIComponent(documentId)}/rebuild`;
      const capability = await applyCapability(
        "vector-index:rebuild",
        path,
        "",
        `vector-index-rebuild:${documentId}`,
        2,
      );
      return requestJson<VectorIndexRebuildResponse>(path, {
        method: "POST",
        headers: {
          "X-Capability-Token": capability.token,
          "X-Request-Hash": capability.requestHash,
        },
      });
    },
  };
}

/**
 * Reads the saved Sa-Token session from localStorage.
 */
function readAuthSession(): LoginResponse | null {
  try {
    const value = globalThis.localStorage?.getItem(AUTH_STORAGE_KEY);
    return value ? (JSON.parse(value) as LoginResponse) : null;
  } catch {
    return null;
  }
}

/**
 * Hashes the exact request body that will consume a capability token.
 */
async function hashRequestBody(body: string): Promise<string> {
  const subtle = globalThis.crypto?.subtle;
  if (subtle) {
    const digest = await subtle.digest("SHA-256", new TextEncoder().encode(body));
    return `sha256:${Array.from(new Uint8Array(digest), byteToHex).join("")}`;
  }
  let hash = 2166136261;
  for (let index = 0; index < body.length; index += 1) {
    hash ^= body.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `fnv1a32:${(hash >>> 0).toString(16).padStart(8, "0")}`;
}

/**
 * Converts a digest byte into two lowercase hex characters.
 */
function byteToHex(value: number): string {
  return value.toString(16).padStart(2, "0");
}
