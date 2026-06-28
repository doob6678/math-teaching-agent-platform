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
  questionText: string;
  /** 用户想学什么。 */
  learningGoal: string;
  /** 教材证据召回数量上限。 */
  evidenceLimit: number;
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
  /** 后续交互建议。 */
  interactiveSuggestions: string[];
  /** 学生记忆复用决策。 */
  memoryReuse?: TeachingMemoryReuse;
  /** 后端 DAG 阶段耗时统计。 */
  stageTimings?: TeachingStageTiming[];
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
  /** Export status; current baseline returns COMPLETED synchronously. */
  status: string;
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
  /** Data scopes requested by the workflow. */
  requestedDataScopes: string[];
  /** Whether this run can spend high-value model/tool budget. */
  highValueOperation: boolean;
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
}

/**
 * 学生学习画像响应。字段与后端 `StudentDashboardResponse` 对齐，用于学生端进度图谱、薄弱点和历史记录展示。
 */
export interface StudentDashboardResponse {
  /** 租户 ID，用于学校或机构维度的数据隔离。 */
  tenantId: string;
  /** 当前面板展示的学生 ID。 */
  studentId: string;
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
  /** 飞书知识库链接或占位链接。 */
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
}

/**
 * 教师资料源响应，用于后台预览、删除和重建索引状态展示。
 */
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

type FetchLike = (
  input: string,
  init?: RequestInit,
) => Promise<Pick<Response, "ok" | "status" | "json" | "text" | "arrayBuffer">>;

const AUTH_STORAGE_KEY = "math-agent:auth-session";
const DEVICE_ID_HEADER = { "X-Device-Id": "local-browser-console" };

export function createTextbookApiClient(baseUrl: string, fetchImpl: FetchLike = fetch) {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, "");

  /**
   * 请求后端 JSON。身份只通过后端登录 token 传递，不能使用前端自报角色或学生 ID。
   */
  async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Backend request failed: ${response.status} ${body}`.trim());
    }
    return response.json() as Promise<T>;
  }

  /**
   * Requests a backend text response while preserving the same session and device headers.
   */
  async function requestText(path: string, init: RequestInit = {}): Promise<string> {
    const auth = readAuthSession();
    const authHeader = auth ? { [auth.tokenName]: auth.tokenValue } : {};
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Backend request failed: ${response.status} ${body}`.trim());
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
      headers: {
        ...DEVICE_ID_HEADER,
        ...authHeader,
        ...init.headers,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Backend request failed: ${response.status} ${body}`.trim());
    }
    return new Uint8Array(await response.arrayBuffer());
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
     * 读取教材资源摘要。
     */
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

    /**
     * 读取学生学习画像。默认使用本地学生身份，避免学生面板误带教师权限。
     */
    /**
     * Downloads the LaTeX handout for a teaching task after applying a one-time capability token.
     */
    async exportTeachingTaskLatex(taskId: string): Promise<string> {
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/handout/latex`;
      const capability = await applyCapability(
        "teaching-handout:export-latex",
        path,
        "",
        `teaching-handout-export-latex:${taskId}`,
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
    async previewTeachingTaskLatex(taskId: string): Promise<string> {
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/handout/latex/preview`;
      const capability = await applyCapability(
        "teaching-handout:preview-latex",
        path,
        "",
        `teaching-handout-preview-latex:${taskId}`,
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
    async exportTeachingTaskPdf(taskId: string): Promise<Uint8Array> {
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/handout/pdf`;
      const capability = await applyCapability(
        "teaching-handout:export-pdf",
        path,
        "",
        `teaching-handout-export-pdf:${taskId}`,
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
     * Creates a short-lived backend ZIP package for selected handouts and folder grouping.
     */
    async createTeachingHandoutBatchZip(
      request: TeachingHandoutBatchExportRequest,
    ): Promise<TeachingHandoutBatchExportResponse> {
      const body = JSON.stringify(request);
      const path = "/api/teaching/handouts/batch/zip";
      const idempotencyKey = `teaching-handout-batch-export-zip:${[
        ...(request.folderIds ?? []),
        request.taskIds.join(","),
      ].join(":")}`;
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
     * Plans an AI agent run using backend session identity and server-side policy.
     */
    planAgentRun(request: AgentRunPlanRequest): Promise<AgentRunPlanResponse> {
      return requestJson<AgentRunPlanResponse>("/api/agents/run-plan", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    getStudentDashboard(studentId?: string): Promise<StudentDashboardResponse> {
      const params = new URLSearchParams();
      if (studentId) {
        params.set("studentId", studentId);
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      return requestJson<StudentDashboardResponse>(`/api/students/dashboard${suffix}`);
    },

    /**
     * 读取当前教师可见的资料源列表。
     */
    listTeacherResources(): Promise<TeacherResourceDocumentResponse[]> {
      return requestJson<TeacherResourceDocumentResponse[]>("/api/teacher/resources");
    },

    /**
     * 登记教师资料源，后端会返回预览和等待重建索引状态。
     */
    registerTeacherResource(
      request: TeacherResourceRegistrationRequest,
    ): Promise<TeacherResourceDocumentResponse> {
      const body = JSON.stringify(request);
      return applyCapability(
        "teacher-resource:register",
        "/api/teacher/resources",
        body,
        `teacher-resource-register:${request.sourceType}:${request.title}`,
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
