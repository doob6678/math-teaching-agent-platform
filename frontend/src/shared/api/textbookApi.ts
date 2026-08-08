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
  /** 后端根据真实阶段生成的中文结论，明确区分无证据和成功命中。 */
  retrievalDescription: string;
  /** BGE、CLIP、重排和缓存的实际阶段状态。 */
  retrievalStages: TextbookRetrievalStage[];
  /** 返回 hit 数量。 */
  total: number;
  /** 命中的教材证据列表。 */
  hits: TextbookSearchHit[];
}
/** User-visible execution state returned by the textbook RAG backend. */
export interface TextbookRetrievalStage {
  code: string;
  label: string;
  status: "completed" | "no_evidence" | "empty" | "hit" | string;
  description: string;
  /** Measured backend time for this stage; omitted for cache-only or skipped stages. */
  elapsedMs?: number;
}

/** Configurable textbook RAG request. Formula images are sent only to the local CLIP worker. */
export interface TextbookSearchOptions {
  query: string;
  formulaQuery?: string;
  formulaImage?: string;
  limit: number;
  documentIds?: string[];
  retrievalMode?: "hybrid" | "text_bge" | "formula_bge" | "image_clip";
}

/** User-visible textbook strategies. The backend owns the actual stage semantics. */
export const TEXTBOOK_RETRIEVAL_MODES = [
  { value: "hybrid", label: "混合检索", description: "BM25 + BGE 文本页召回 + 后端重排" },
  { value: "text_bge", label: "文本语义", description: "BGE 文本页召回 + 后端重排" },
  { value: "formula_bge", label: "公式语义", description: "公式/主题文本走 BGE 文本页召回" },
  { value: "image_clip", label: "图片检索", description: "明确使用 CLIP 搜索教材页面图片" },
] as const;

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
}

/**
 * Administrator-only teacher provisioning input. Tenant and role remain server-owned so the browser cannot assign
 * the new account to another tenant or elevate it beyond the fixed teacher role.
 */
export interface TeacherAccountProvisionRequest {
  /** Unique login name selected for the new teacher. */
  username: string;
  /** Initial password sent only to the backend account store over the current authenticated connection. */
  password: string;
}

/**
 * Password-free account metadata returned after an administrator creates a teacher account.
 */
export interface TeacherAccountProvisionResponse {
  /** Stable backend subject id for the new teacher. */
  userId: string;
  /** Newly created teacher login name. */
  username: string;
  /** Backend-enforced fixed role, expected to be teacher. */
  role: string;
  /** Tenant inherited from the authenticated administrator session. */
  tenantId: string;
}

/**
 * 登录响应。原始会话凭证只存在后端设置的 HttpOnly Cookie 中，浏览器仅保留非敏感会话元数据。
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
  /** Printable PDF attribution; empty values fall back to the neutral server default “数学讲义”. */
  watermarkText?: string;
  /** Optional backend allow-listed provider selected for this handout only. */
  aiProviderName?: string;
  /** Optional backend allow-listed model selected for this handout only. */
  aiModelCode?: string;
  /** Supplementary layout/review requirements; never treated as printable question text. */
  supplementaryRequirements?: string;
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
  blankSpaceEm?: number | null;
  questionGapEm?: number | null;
}

export interface TeachingHandoutPdfResponse {
  bytes: Uint8Array;
  renderer: string;
  pageCount: number;
}

export type TeachingHandoutVersion = "teacher" | "student" | "lecture";

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
 * Recoverable teaching workflow event for process-stream style UI and future event-table persistence.
 */
export interface TeachingWorkflowEvent {
  /** Stable event id within one teaching task response. */
  eventId: string;
  /** Parent event id; empty when this is a root-level event. */
  parentEventId?: string;
  /** Producer type, such as system, tool, agent, or reviewer. */
  sourceType: string;
  /** Producer display name. */
  sourceName: string;
  /** Stable event kind, such as plan, evidence, generation, render, or review. */
  eventType: string;
  /** Event status for the persisted snapshot. */
  status: string;
  /** Short user-facing title. */
  title: string;
  /** Safe summary without raw prompt, token, or debug leakage. */
  summary: string;
  /** Evidence scopes or artifact versions produced by this event. */
  artifactRefs: string[];
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
  /** Backend-authorized local textbook page image used only by PDF rendering; never exposed as a browser file path. */
  imagePath?: string;
  /** Opaque teacher-resource document id used to request the full authorized block; never a local path or Feishu token. */
  sourceDocumentId?: string;
  /** Normalized backend source type, for example feishu or public_textbook. */
  sourceType?: string;
  /** Original source URL when the backend has verified and stored one. */
  sourceUrl?: string;
  /** Human-readable path inside the source document or textbook corpus. */
  sourcePath?: string;
  /** Opaque image asset ids attached to this evidence block. */
  assetIds?: string[];
}

/** One full parsed teacher-resource block returned only after the backend checks the current session owner. */
export interface TeacherDocumentBlockResponse {
  blockId: string;
  documentId: string;
  blockType: string;
  blockOrder: number;
  chapter?: string;
  section?: string;
  pageNo?: number | null;
  blockRole?: string;
  rawText: string;
  imageRefs?: string;
  formulaRefs?: string;
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

/** One student answer fact used to update knowledge-point mastery. */
export interface StudentLearningAttemptRequest {
  questionId: string;
  questionText?: string;
  knowledgePointIds: string[];
  correct: boolean;
  responseTimeMs: number;
}

/** Explainable mastery projection returned by the learning loop. */
export interface StudentKnowledgeMasteryResponse {
  tenantId: string;
  studentId: string;
  knowledgePointId: string;
  masteryPercent: number;
  attemptCount: number;
  correctCount: number;
  incorrectCount: number;
  weaknessLevel: number;
  lastAttemptAt?: string;
  evidenceSummary: string;
}

export interface StudentLearningPathStep {
  knowledgePointId: string;
  knowledgePointName: string;
  masteryPercent: number;
  weaknessLevel: number;
  relationToNext: string;
  recommendation: string;
}

export interface StudentLearningPathResponse {
  studentId: string;
  steps: StudentLearningPathStep[];
  generatedFrom: string;
}

export interface StudentLearningIntentResponse {
  intentCode: string;
  confidence: number;
  knowledgePointId?: string;
  knowledgePointName?: string;
  suggestedApi?: string;
  recognizedBy: string;
}

export interface StudentLearningAttemptResponse {
  attemptId: string;
  updatedMastery: StudentKnowledgeMasteryResponse[];
  weakPoints: StudentKnowledgeMasteryResponse[];
}

export interface StudentLearningRecommendationResponse {
  question: QuestionBankItemResponse;
  knowledgePointId: string;
  weaknessLevel: number;
}

/** Request for a student explanation that carries the current weak-point context. */
export interface TargetedStudentExplanationRequest {
  questionText: string;
  knowledgePointId?: string;
  questionId?: string;
}

/** Request for a teacher handout assembled from diagnosed weak points and linked question-bank items. */
export interface TargetedLearningHandoutRequest {
  clientRequestId: string;
  studentId?: string;
  knowledgePointId?: string;
  questionLimit: number;
  handoutTemplateCode?: string;
  evidenceLimit: number;
}

/** Student-owned practice generation request; the backend returns only student-safe task fields. */
export interface TargetedPracticeRequest {
  clientRequestId: string;
  knowledgePointId?: string;
  exerciseCount: number;
  evidenceLimit: number;
}

export interface StudentPracticeTaskResponse {
  taskId: string;
  clientRequestId: string;
  status: string;
  studentId: string;
  knowledgePointIds: string[];
  questionText: string;
  learningGoal: string;
  studentHandoutLatex?: string;
  interactiveSuggestions: string[];
  errorMessage?: string;
}

/**
 * Structured teaching draft sections collected before review and merge.
 */
export interface TeachingDraftSections {
  /** Teacher-facing explanation draft. */
  teacherExplanation: string;
  /** Student-safe worksheet draft. */
  studentWorksheet: string;
  /** Lecture-card outline derived from the teacher draft. */
  lectureCards: string[];
  /** Structured student exercises. */
  exercises: string[];
  /** Trace-safe evidence references used by the draft. */
  sourceRefs: string[];
  /** Known review risks that later reviewer agents should resolve. */
  risks: string[];
}

/**
 * Structured review result collected before merge/render decisions.
 */
export interface TeachingDraftReview {
  /** READY or NEEDS_ATTENTION. */
  status: string;
  /** Structured findings grouped by reviewer role and section. */
  findings: TeachingDraftReviewFinding[];
  /** Merge-ready patch suggestions. */
  patches: TeachingDraftReviewPatch[];
}

export interface TeachingDraftReviewFinding {
  reviewerCode: string;
  severity: string;
  sectionCode: string;
  summary: string;
  artifactRefs: string[];
}

export interface TeachingDraftReviewPatch {
  reviewerCode: string;
  targetSectionCode: string;
  instruction: string;
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
  /** Persisted PDF attribution used by every preview and export for this task. */
  watermarkText?: string;
  /** DAG 节点。 */
  nodes: TeachingWorkflowNode[];
  /** Recoverable process events, richer than nodes and safe for progress timelines. */
  workflowEvents?: TeachingWorkflowEvent[];
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
  /** Lecture/PPT-style version extracted from the teacher handout for projector review. */
  lectureHandoutLatex?: string;
  /** 后续交互建议。 */
  interactiveSuggestions: string[];
  /** 学生记忆复用决策。 */
  memoryReuse?: TeachingMemoryReuse;
  /** 后端 DAG 阶段耗时统计。 */
  stageTimings?: TeachingStageTiming[];
  aiDraft?: TeachingAiDraft;
  /** Structured draft sections collected before review/merge/render. */
  draftSections?: TeachingDraftSections;
  /** Structured review findings and patch suggestions. */
  draftReview?: TeachingDraftReview;
  /** 失败原因。 */
  errorMessage?: string;
}

/** Availability of the three artifacts generated by one owned teaching task. */
export interface TeachingHandoutVersionAvailability {
  teacherReady: boolean;
  studentReady: boolean;
  lectureReady: boolean;
}

/**
 * Safe, incremental teaching-task snapshot carried by SSE. It intentionally omits raw model output and full LaTeX;
 * the completed task endpoint remains the authoritative source for preview and editing content.
 */
export interface TeachingTaskProgressResponse {
  taskId: string;
  status: TeachingTaskResponse["status"];
  nodes: TeachingWorkflowNode[];
  workflowEvents: TeachingWorkflowEvent[];
  evidence: TeachingEvidence[];
  stageTimings: TeachingStageTiming[];
  versions: TeachingHandoutVersionAvailability;
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
  /** Stable stage code in the controlled writing topology. */
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
  /** Measured backend wall-clock duration for this stage; omitted by historical workflow snapshots. */
  elapsedMs?: number;
}

/** Backend-filtered marketplace catalog; the browser may select a card but cannot grant any scope. */
export interface AgentRegistryResponse {
  agents: AgentRegistryItem[];
}

/** One discoverable specialist in the multi-agent plaza. */
export interface AgentRegistryItem {
  code: string;
  name: string;
  category: string;
  description: string;
  allowedToolScopes: string[];
  allowedDataScopes: string[];
  inputHint: string;
  outputArtifactType: string;
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
  /** Merge-ready sections with review metadata for collaborative editing. */
  sections?: MultiAgentWritingStructuredSection[];
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
 * Structured document section created from multi-agent writing output.
 */
export interface MultiAgentWritingStructuredSection {
  /** Stable merge section code. */
  sectionCode: string;
  /** Readable section title. */
  title: string;
  /** Stage that produced this section. */
  sourceStageCode: string;
  /** Owner-visible Markdown body. */
  content: string;
  /** Reviewer comments or patch notes for this section. */
  reviewNotes: string[];
  /** Known content, layout, or evidence risks. */
  risks: string[];
  /** Evidence or upstream artifact references used by this section. */
  artifactRefs: string[];
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
  /**
   * Legacy temporary exports had a server-side expiry. Teaching-task exports are downloaded directly from the
   * authoritative task, so there is no temporary object to expire.
   */
  expiresAt?: string;
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
  /** Optional question text typed by the student; an uploaded original image is sent directly to the AI model. */
  questionText?: string;
  /** Backend-issued temporary upload id from the real image upload endpoint. */
  imageUploadId?: string;
  /** Optional image file name; this is display metadata only. */
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
  /** Explicit opt-in for earlier turns from the current conversation. Defaults to false. */
  useConversationMemory?: boolean;
  /** Stable client-generated idempotency key used to resume one explanation run after reconnect. */
  clientRequestId?: string;
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
  /** Explicit status; upload stores the original for direct multimodal context and performs no standalone OCR. */
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
  title: string;
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
 * Sidebar-safe conversation shell for the AI explanation workspace.
 */
export interface StudentExplanationConversationSummary {
  conversationId: string;
  title: string;
  lastQuestionText?: string;
  viewerRole: string;
  totalMessages: number;
  createdAt: string;
  updatedAt: string;
}

export interface StudentExplanationConversationListResponse {
  items: StudentExplanationConversationSummary[];
}

/**
 * Backend response for student-side explanation cards.
 */
export interface StudentExplanationResponse {
  /** Server-generated explanation id for trace and retry correlation. */
  explanationId: string;
  /** Durable conversation id for follow-up context and history recovery. */
  conversationId: string;
  /** Short title shown in AI 讲题页和最近讲题列表。 */
  conversationTitle: string;
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
 * Real-time progress snapshot for student explanation streaming.
 */
export interface StudentExplanationStreamProgress {
  conversationId: string;
  conversationTitle: string;
  questionText?: string;
  imageStatus: string;
  imageUnderstanding: StudentExplanationImageUnderstanding;
  aiDraft: StudentExplanationAiDraft;
  workflowStages: StudentExplanationStage[];
  cards: StudentExplanationCard[];
  sources: StudentExplanationSource[];
  totalElapsedMs: number;
}

/**
 * One SSE event returned by the streaming student explanation endpoint.
 */
export interface StudentExplanationStreamEvent {
  eventType: string;
  message?: string;
  progress?: StudentExplanationStreamProgress | null;
  response?: StudentExplanationResponse | null;
  errorCode?: string | null;
  errorTraceId?: string | null;
  /** Actual provider content received since the previous SSE event. */
  aiContentDelta?: string | null;
  /** Actual provider reasoning received since the previous SSE event. */
  aiReasoningDelta?: string | null;
  /** Cards whose complete JSON objects have already arrived from the provider. */
  cards?: StudentExplanationCard[] | null;
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
  /** Display path shown beside the link. */
  sourcePath?: string;
  /** Backend-provided local or remote open URL. */
  openUrl?: string;
}

export interface StudentExplanationConversationMessage {
  explanationId: string;
  questionText?: string;
  imageStatus: string;
  imageProblemText?: string;
  imageFileName?: string;
  createdAt: string;
  response: StudentExplanationResponse;
}

export interface StudentExplanationConversationResponse {
  conversationId: string;
  title: string;
  viewerRole: string;
  totalMessages: number;
  createdAt: string;
  updatedAt: string;
  messages: StudentExplanationConversationMessage[];
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
  /** 来源类型，例如 feishu、local_path、teacher_resource、qq_bundle、gaokao 或 mock_exam。 */
  sourceType: string;
  /** Optional explicit title override for advanced callers; the normal browser flow now lets the backend derive it. */
  title?: string;
  /** 飞书或外部来源 URL。 */
  originalUrl?: string;
  /** 本地文件或文件夹路径。 */
  localPath?: string;
  /** RAG 权限域，例如 TEACHER_PRIVATE、MATH_VIP 或 PUBLIC_TEXTBOOK。 */
  permissionScope: string;
  /** Native Feishu export format for Feishu resources; defaults to md. */
  feishuExportFormat?: "md" | "docx" | "pdf";
  /** TEXT uses deterministic extraction; AI requests higher-cost semantic labeling when backend is configured. */
  parseMode?: "TEXT" | "MARKDOWN_ASSETS" | "AI";
}

/**
 * 浏览器上传教师资料时的 multipart 请求体。
 */
export interface TeacherResourceUploadRequest {
  /** 非飞书资料的逻辑库类型；最终仍复用后端既有 local sync 链路。 */
  sourceType?: string;
  /** Optional explicit title override for advanced callers; the normal upload UI no longer sends this field. */
  title?: string;
  /** RAG 权限域，例如 TEACHER_PRIVATE、MATH_VIP 或 PUBLIC_TEXTBOOK。 */
  permissionScope: string;
  /** TEXT uses deterministic extraction; AI requests higher-cost semantic labeling when backend is configured. */
  parseMode?: "TEXT" | "MARKDOWN_ASSETS" | "AI";
  /** 上传的真实文件，可来自多文件选择、文件夹选择或 ZIP。 */
  files: File[];
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
  /** TEXT deterministic extraction or AI semantic labeling mode. */
  parseMode?: "TEXT" | "AI";
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

/**
 * 教师资料检索筛选项。
 *
 * 这里只暴露逻辑库 `libraries`，不再暴露旧 `sourceType` 检索筛选。`sourceType` 仍然是资源元数据，
 * 但检索阶段必须按 QQ/飞书/真题/模拟题/教材 这些逻辑库来约束，避免 AI 调用把存储实现细节和业务库范围混在一起。
 */
export interface TeacherResourceSearchOptions {
  permissionScopes?: string[];
  documentIds?: string[];
  libraries?: string[];
  tags?: string[];
}

export interface TeacherResourceBlockSearchHit {
  documentId: string;
  documentTitle: string;
  sourceType?: string;
  permissionScope: string;
  blockId: string;
  blockType: string;
  blockOrder: number;
  chapter?: string;
  section?: string;
  pageNo?: number | null;
  sourcePath?: string;
  blockRole?: string;
  graphTags?: string[];
  evidenceBlockIds?: string[];
  evidenceText?: string;
  snippet: string;
  score: number;
  imageAssetIds?: string[];
  assetRefs?: TeacherResourceAssetReference[];
}

export interface TeacherResourceAssetReference {
  assetId: string;
  assetUri: string;
  mimeType?: string;
  fileName?: string;
  sourcePath?: string;
  pageNo?: number | null;
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
  /** Real provider authorization metadata persisted with a failed Feishu job. */
  failure?: {
    providerCode?: string | null;
    retryable: boolean;
    requiredScopes: string[];
    authorizationUrl?: string | null;
  };
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
    mode: string;
  };
  redis: {
    redissonEnabled: boolean;
    redissonAddress: string;
    rateLimitEnabled: boolean;
    rateLimitKeyPrefix: string;
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
) => Promise<Pick<Response, "ok" | "status" | "json" | "text" | "arrayBuffer" | "headers" | "body">>;

/** Broadcast when the backend rejects the HttpOnly cookie whose server-side session has disappeared. */
export const AUTH_INVALID_EVENT = "math-agent:auth-invalid";
/** Task data, protected artifacts, and SSE snapshots must never reuse a stale browser/proxy response. */
const NO_STORE_HEADERS = {
  "Cache-Control": "no-store, no-cache, max-age=0",
  Pragma: "no-cache",
};
/** Upper bound fetched for one real question-bank query before the UI applies visible page slicing. */
export const QUESTION_BANK_MAX_SEARCH_ROWS = 500;
/** Matches the Java compatibility facade so a retried legacy request resolves to the same teaching task. */
const LEGACY_HANDOUT_CLIENT_REQUEST_PREFIX = "writing-";
/** The teaching submission contract requires a positive, bounded evidence count. */
const MIN_HANDOUT_EVIDENCE_LIMIT = 1;
const MAX_HANDOUT_EVIDENCE_LIMIT = 24;
/** Prevents a large binary conversion from exceeding browser argument limits while preserving every byte. */
const BASE64_BINARY_CHUNK_BYTES = 0x8000;

export function createTextbookApiClient(baseUrl: string, fetchImpl: FetchLike = fetch) {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, "");
  /**
   * 请求后端 JSON。身份只通过浏览器自动携带的 HttpOnly Cookie 解析，不能使用前端自报角色或学生 ID。
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
   * Computes the exact opaque client request id used by the Java compatibility facade. Keeping this deterministic
   * lets a browser retry recover the same teaching task without putting the question text in persistent metadata.
   */
  async function legacyHandoutClientRequestId(request: MultiAgentWritingRequest): Promise<string> {
    const canonical = [
      request.writingGoal.trim(),
      request.questionText.trim(),
      request.evidenceRefs.join("\u001e"),
      "false",
      request.preferredProviderName?.trim() ?? "",
      request.preferredModelCode?.trim() ?? "",
    ].join("\u001f");
    if (!globalThis.crypto?.subtle) {
      throw new Error("Secure browser cryptography is required to submit a recoverable handout task");
    }
    const digest = new Uint8Array(await globalThis.crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(canonical),
    ));
    return `${LEGACY_HANDOUT_CLIENT_REQUEST_PREFIX}${bytesToBase64(digest)
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "")}`;
  }

  /** Converts the old browser request to the sole persisted teaching-task creation contract. */
  async function toTeachingHandoutRequest(request: MultiAgentWritingRequest): Promise<TeachingTaskRequest> {
    return {
      clientRequestId: await legacyHandoutClientRequestId(request),
      questionText: request.questionText.trim(),
      learningGoal: request.writingGoal.trim(),
      evidenceLimit: Math.max(
        MIN_HANDOUT_EVIDENCE_LIMIT,
        Math.min(MAX_HANDOUT_EVIDENCE_LIMIT, request.evidenceRefs.length),
      ),
      aiProviderName: request.preferredProviderName?.trim() || undefined,
      aiModelCode: request.preferredModelCode?.trim() || undefined,
    };
  }

  /** The task snapshot is the source of the retained panel shape; workflowId is only a display compatibility alias. */
  function projectTeachingTaskAsWritingWorkflow(task: TeachingTaskResponse): MultiAgentWritingResponse {
    const noLedgerUsage: AgentTokenUsage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
    return {
      workflowId: task.taskId,
      tenantId: task.tenantId ?? "",
      subjectType: task.subjectType ?? "",
      subjectId: task.subjectId ?? "",
      status: task.status,
      stages: task.nodes.map((node) => ({
        stageCode: node.code,
        agentCode: "teaching-task",
        traceId: `${task.taskId}:${node.code}`,
        providerName: "teaching-task",
        modelCode: "",
        status: node.status,
        actualUsage: noLedgerUsage,
        message: node.summary,
      })),
      // A teaching snapshot currently has task-level usage only. Per-node token fields remain zero until the durable
      // usage ledger is projected, rather than assigning the same billable total to several writer nodes.
      totalUsage: task.aiDraft
        ? {
          promptTokens: task.aiDraft.promptTokens,
          completionTokens: task.aiDraft.completionTokens,
          totalTokens: task.aiDraft.totalTokens,
        }
        : noLedgerUsage,
      message: task.errorMessage || task.aiDraft?.message || `Teaching task ${task.status.toLowerCase()}`,
    };
  }

  /** Reuses durable node and event data for the legacy trace panel without querying the retired trace store. */
  function projectTeachingTaskAsWritingTraces(task: TeachingTaskResponse): MultiAgentWritingTraceResponse {
    const noLedgerUsage: AgentTokenUsage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
    const stages = task.nodes.map((node) => ({
      traceId: `${task.taskId}:${node.code}`,
      planId: `${task.taskId}:${node.code}`,
      createdAt: "",
      tenantId: task.tenantId ?? "",
      subjectType: task.subjectType ?? "",
      subjectId: task.subjectId ?? "",
      agentCode: node.code,
      providerName: "teaching-task",
      modelCode: "",
      status: node.status,
      estimatedCost: -1,
      allowedToolScopes: [],
      allowedDataScopes: [],
      evidenceRefs: task.workflowEvents
        ?.filter((event) => event.eventType === node.code)
        .flatMap((event) => event.artifactRefs) ?? [],
      stageTimings: [],
      actualUsage: noLedgerUsage,
      message: node.summary,
      diagnosticEvents: [],
    }));
    return {
      workflowId: task.taskId,
      tenantId: task.tenantId ?? "",
      subjectType: task.subjectType ?? "",
      subjectId: task.subjectId ?? "",
      stageCount: stages.length,
      totalUsage: task.aiDraft
        ? {
          promptTokens: task.aiDraft.promptTokens,
          completionTokens: task.aiDraft.completionTokens,
          totalTokens: task.aiDraft.totalTokens,
        }
        : noLedgerUsage,
      stages,
    };
  }

  /** Projects only task-owned teacher, student, and lecture artifacts into the retained review-panel shape. */
  function projectTeachingTaskAsWritingArtifact(task: TeachingTaskResponse): MultiAgentWritingArtifact {
    const noLedgerUsage: AgentTokenUsage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
    const sections = task.draftSections
      ? [
        {
          sectionCode: "teacher",
          title: "教师版讲义",
          sourceStageCode: "teacher_writer",
          content: task.draftSections.teacherExplanation,
          reviewNotes: [],
          risks: task.draftSections.risks,
          artifactRefs: task.draftSections.sourceRefs,
        },
        {
          sectionCode: "student",
          title: "学生版讲义",
          sourceStageCode: "student_writer",
          content: task.draftSections.studentWorksheet,
          reviewNotes: [],
          risks: task.draftSections.risks,
          artifactRefs: task.draftSections.sourceRefs,
        },
        {
          sectionCode: "lecture",
          title: "课堂讲解版",
          sourceStageCode: "lecture_writer",
          content: task.draftSections.lectureCards.join("\n\n"),
          reviewNotes: [],
          risks: task.draftSections.risks,
          artifactRefs: task.draftSections.sourceRefs,
        },
      ]
      : undefined;
    return {
      workflowId: task.taskId,
      tenantId: task.tenantId ?? "",
      subjectType: task.subjectType ?? "",
      subjectId: task.subjectId ?? "",
      status: task.status,
      totalUsage: task.aiDraft
        ? {
          promptTokens: task.aiDraft.promptTokens,
          completionTokens: task.aiDraft.completionTokens,
          totalTokens: task.aiDraft.totalTokens,
        }
        : noLedgerUsage,
      stages: [
        {
          stageCode: "teacher_writer",
          agentCode: "teaching-task",
          traceId: `${task.taskId}:teacher_writer`,
          providerName: "teaching-task",
          modelCode: "",
          status: task.status,
          generatedContent: task.teacherHandoutLatex ?? "",
        },
        {
          stageCode: "student_writer",
          agentCode: "teaching-task",
          traceId: `${task.taskId}:student_writer`,
          providerName: "teaching-task",
          modelCode: "",
          status: task.status,
          generatedContent: task.studentHandoutLatex ?? "",
        },
        {
          stageCode: "lecture_writer",
          agentCode: "teaching-task",
          traceId: `${task.taskId}:lecture_writer`,
          providerName: "teaching-task",
          modelCode: "",
          status: task.status,
          generatedContent: task.lectureHandoutLatex ?? "",
        },
      ],
      sections,
      mergedMarkdown: task.aiDraft?.content || task.handoutLatex,
    };
  }

  /** Encodes actual response bytes for the retained download caller; chunking avoids spreading an arbitrary buffer. */
  function bytesToBase64(bytes: Uint8Array): string {
    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += BASE64_BINARY_CHUNK_BYTES) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + BASE64_BINARY_CHUNK_BYTES));
    }
    return globalThis.btoa(binary);
  }

  /** Adds integrity metadata to a direct teaching-task download without inventing a temporary server export object. */
  async function sha256Hex(bytes: Uint8Array): Promise<string> {
    if (!globalThis.crypto?.subtle) {
      throw new Error("Secure browser cryptography is required to export a handout");
    }
    // Copy into an owned ArrayBuffer because a response view can also be backed by SharedArrayBuffer, which Web
    // Crypto deliberately rejects even though it is safe to read for the download itself.
    const digestInput = new Uint8Array(bytes.byteLength);
    digestInput.set(bytes);
    const digest = new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", digestInput.buffer));
    return Array.from(digest, (value) => value.toString(16).padStart(2, "0")).join("");
  }

  /** Builds the old transport-only download envelope from bytes already authorized by a teaching-task endpoint. */
  async function directTeachingExport(
    taskId: string,
    format: string,
    fileName: string,
    mimeType: string,
    bytes: Uint8Array,
  ): Promise<MultiAgentWritingArtifactExportResponse> {
    return {
      exportId: generateUUID(),
      workflowId: taskId,
      format,
      fileName,
      mimeType,
      byteSize: bytes.byteLength,
      sha256: await sha256Hex(bytes),
      base64Content: bytesToBase64(bytes),
    };
  }

  /**
   * Turns structured backend errors into concise UI text; raw JSON makes operational pages hard to read.
   */
  function backendErrorMessage(status: number, body: string): string {
    const trimmed = body.trim();
    if (trimmed.startsWith("{")) {
      try {
        const parsed = JSON.parse(trimmed) as { message?: unknown; code?: unknown; error?: unknown; traceId?: unknown };
        const message = typeof parsed.message === "string" ? parsed.message : "";
        const code = typeof parsed.code === "string" ? parsed.code : "";
        const traceId = typeof parsed.traceId === "string" ? parsed.traceId : "";
        if (message && code) {
          return `Backend request failed: ${status} ${message}${traceId ? ` [traceId: ${traceId}]` : ""} (${code})`;
        }
        if (message) {
          return `Backend request failed: ${status} ${message}${traceId ? ` [traceId: ${traceId}]` : ""}`;
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

  /**
   * Clears a demonstrably stale login immediately after a backend restart or session expiry.
   * Other policy 403s remain visible and must never silently log the user out.
   */
  function invalidateStaleSession(status: number, body: string): void {
    const expiredSession = status === 403
      && /SESSION_EXPIRED|AUTH(?:ENTICATION)?_REQUIRED|login session (?:is )?expired/i.test(body);
    if (status !== 401 && !expiredSession) return;
    globalThis.dispatchEvent?.(new Event(AUTH_INVALID_EVENT));
  }

  async function requestJson<T>(
    path: string,
    init: RequestInit = {},
  ): Promise<T> {
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      cache: init.cache ?? "no-store",
      credentials: init.credentials ?? "include",
      headers: {
        ...init.headers,
        ...NO_STORE_HEADERS,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      invalidateStaleSession(response.status, body);
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
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      cache: init.cache ?? "no-store",
      credentials: init.credentials ?? "include",
      headers: {
        ...init.headers,
        ...NO_STORE_HEADERS,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      invalidateStaleSession(response.status, body);
      throw new Error(backendErrorMessage(response.status, body));
    }
    return response.text();
  }

  /**
   * Requests backend binary content while preserving the same session and device headers.
   */
  async function requestBytes(path: string, init: RequestInit = {}): Promise<Uint8Array> {
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      cache: init.cache ?? "no-store",
      credentials: init.credentials ?? "include",
      headers: {
        ...init.headers,
        ...NO_STORE_HEADERS,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      invalidateStaleSession(response.status, body);
      throw new Error(backendErrorMessage(response.status, body));
    }
    return new Uint8Array(await response.arrayBuffer());
  }

  async function requestBytesWithHeaders(path: string, init: RequestInit = {}): Promise<{
    bytes: Uint8Array;
    headers: Headers;
  }> {
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      cache: init.cache ?? "no-store",
      credentials: init.credentials ?? "include",
      headers: {
        ...init.headers,
        ...NO_STORE_HEADERS,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      invalidateStaleSession(response.status, body);
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
  async function requestFormJson<T>(
    path: string,
    formData: FormData,
    init: RequestInit = {},
  ): Promise<T> {
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      cache: init.cache ?? "no-store",
      method: init.method ?? "POST",
      credentials: init.credentials ?? "include",
      headers: {
        ...init.headers,
        ...NO_STORE_HEADERS,
      },
      body: formData,
    });
    if (!response.ok) {
      const body = await response.text();
      invalidateStaleSession(response.status, body);
      throw new Error(backendErrorMessage(response.status, body));
    }
    return response.json() as Promise<T>;
  }

  /**
   * Opens a POST-based SSE stream and yields parsed event payloads to the caller.
   */
  async function requestEventStream<T>(
    path: string,
    init: RequestInit,
    onEvent: (eventName: string, payload: T) => void,
  ): Promise<void> {
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      cache: init.cache ?? "no-store",
      credentials: init.credentials ?? "include",
      headers: {
        Accept: "text/event-stream",
        ...init.headers,
        ...NO_STORE_HEADERS,
      },
    });
    if (!response.ok) {
      const body = await response.text();
      invalidateStaleSession(response.status, body);
      throw new Error(backendErrorMessage(response.status, body));
    }
    if (!response.body) {
      throw new Error("浏览器未返回可读取的流式响应。");
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done }).replace(/\r\n/g, "\n");
      let separatorIndex = buffer.indexOf("\n\n");
      while (separatorIndex >= 0) {
        const rawEvent = buffer.slice(0, separatorIndex);
        buffer = buffer.slice(separatorIndex + 2);
        const parsed = parseServerSentEvent<T>(rawEvent);
        if (parsed) {
          onEvent(parsed.eventName, parsed.payload);
        }
        separatorIndex = buffer.indexOf("\n\n");
      }
      if (done) {
        const trailing = buffer.trim();
        if (trailing) {
          const parsed = parseServerSentEvent<T>(trailing);
          if (parsed) {
            onEvent(parsed.eventName, parsed.payload);
          }
        }
        return;
      }
    }
  }

  /**
   * Parses one SSE block into an event name and JSON payload.
   */
  function parseServerSentEvent<T>(rawEvent: string): { eventName: string; payload: T } | null {
    const lines = rawEvent.split(/\r?\n/);
    let eventName = "message";
    const dataLines: string[] = [];
    for (const line of lines) {
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim() || "message";
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    if (!dataLines.length) {
      return null;
    }
    return {
      eventName,
      payload: JSON.parse(dataLines.join("\n")) as T,
    };
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
    /** 登录并接收非敏感会话元数据；后续请求自动携带 HttpOnly Cookie。 */
    async login(request: LoginRequest): Promise<LoginResponse> {
      const response = await requestJson<LoginResponse>("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
      return response;
    },

    /** Registers a student account and receives non-sensitive session metadata. */
    async register(request: RegisterRequest): Promise<LoginResponse> {
      const response = await requestJson<LoginResponse>("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
      return response;
    },

    /**
     * Creates a teacher through the current administrator session without persisting or trusting browser identity,
     * tenant, or role fields. The backend returns only password-free account metadata and keeps this admin session.
     */
    provisionTeacher(request: TeacherAccountProvisionRequest): Promise<TeacherAccountProvisionResponse> {
      return requestJson<TeacherAccountProvisionResponse>("/api/auth/teachers", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: request.username, password: request.password }),
      });
    },

    /**
     * 读取教材资源摘要。
     */
    async currentSession(): Promise<LoginResponse> {
      const response = await requestJson<LoginResponse>("/api/auth/session");
      return response;
    },

    /** Invalidates the backend session cookie; no browser storage cleanup is required because tokens are not persisted. */
    async logout(): Promise<void> {
      await requestJson<void>("/api/auth/logout", { method: "POST" });
    },

    getSummary(): Promise<TextbookSummary> {
      return requestJson<TextbookSummary>("/api/resources/textbooks/summary");
    },

    /**
     * 执行教材证据检索。
     */
    async search(options: TextbookSearchOptions): Promise<TextbookSearchResponse> {
      return requestJson<TextbookSearchResponse>("/api/retrieval/textbooks/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(options),
      });
    },

    /**
     * 按 queryId 读取检索审计详情。
     */
    getAudit(queryId: string): Promise<RetrievalAuditDetail> {
      return requestJson<RetrievalAuditDetail>(`/api/retrieval/audit/${encodeURIComponent(queryId)}`);
    },

    /**
     * 提交可恢复教学任务。前端需要保存返回的 taskId，页面离开后可继续查询。
     */
    async submitTeachingTask(request: TeachingTaskRequest): Promise<TeachingTaskResponse> {
      const body = JSON.stringify(request);
      return requestJson<TeachingTaskResponse>("/api/teaching/tasks", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    /**
     * 按 taskId 读取教学任务结果，用于页面恢复和轮询。
     */
    /**
     * Stores a student memory entry under the authenticated backend subject.
     */
    async rememberStudentMemory(request: StudentMemoryRequest): Promise<StudentMemoryResponse> {
      const body = JSON.stringify(request);
      return requestJson<StudentMemoryResponse>("/api/students/memory/remember", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    getTeachingTask(taskId: string): Promise<TeachingTaskResponse> {
      return requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodeURIComponent(taskId)}`);
    },

    /** Resumes one owned failed task without creating a new generation request. */
    async resumeTeachingTask(taskId: string): Promise<TeachingTaskResponse> {
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/resume`;
      return requestJson<TeachingTaskResponse>(path, { method: "POST" });
    },

    /**
     * Streams durable, frontend-safe snapshots for one owned task. Each event comes from the persisted backend task
     * state, so reconnecting does not invent stage progress and normal task reads remain a recovery fallback.
     */
    streamTeachingTask(
      taskId: string,
      onEvent: (eventName: string, progress: TeachingTaskProgressResponse) => void,
    ): Promise<void> {
      return requestEventStream<TeachingTaskProgressResponse>(
        `/api/teaching/tasks/${encodeURIComponent(taskId)}/events`,
        { method: "GET" },
        onEvent,
      );
    },

    /**
     * Saves one owned handout version on its existing task. The backend applies the same visibility and prompt-leak
     * guards used during generation, so editing never creates a second task or bypasses student-version isolation.
     */
    async updateTeachingTaskHandout(
      taskId: string,
      version: TeachingHandoutVersion,
      latex: string,
    ): Promise<TeachingTaskResponse> {
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/handout/${version}`;
      const body = JSON.stringify({ latex });
      return requestJson<TeachingTaskResponse>(path, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body,
      });
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
     * Downloads the LaTeX handout for an owned teaching task.
     */
    async exportTeachingTaskLatex(taskId: string, version: TeachingHandoutVersion = "teacher"): Promise<string> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/latex`;
      return requestText(path, { method: "GET" });
    },

    /**
     * Loads LaTeX handout source for inline frontend preview.
     */
    async previewTeachingTaskLatex(taskId: string, version: TeachingHandoutVersion = "teacher"): Promise<string> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/latex/preview`;
      return requestText(path, { method: "GET" });
    },

    /**
     * Downloads the PDF handout for an owned teaching task.
     */
    async exportTeachingTaskPdf(taskId: string, version: TeachingHandoutVersion = "teacher"): Promise<TeachingHandoutPdfResponse> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/pdf`;
      const response = await requestBytesWithHeaders(path, { method: "GET" });
      return {
        bytes: response.bytes,
        renderer: response.headers.get("X-Handout-Renderer") ?? "",
        pageCount: Number(response.headers.get("X-Handout-Page-Count") ?? "0") || 0,
      };
    },

    /**
     * Loads the PDF handout for inline frontend preview.
     */
    async previewTeachingTaskPdf(taskId: string, version: TeachingHandoutVersion = "teacher"): Promise<TeachingHandoutPdfResponse> {
      const encodedTaskId = encodeURIComponent(taskId);
      const path = `/api/teaching/tasks/${encodedTaskId}/handout/${version}/pdf/preview`;
      const response = await requestBytesWithHeaders(path, { method: "GET" });
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
      return requestJson<TeachingHandoutBatchExportResponse>(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    /**
     * Downloads a temporary handout ZIP.
     */
    async downloadTeachingHandoutBatchZip(batchId: string): Promise<Uint8Array> {
      const path = `/api/teaching/handouts/batch/zip/${encodeURIComponent(batchId)}/download`;
      return requestBytes(path, { method: "GET" });
    },

    /**
     * Submits human feedback for an owned teaching task.
     */
    async submitTeachingHumanFeedback(
      taskId: string,
      request: TeachingHumanFeedbackRequest,
    ): Promise<TeachingHumanFeedbackResponse> {
      const body = JSON.stringify(request);
      const path = `/api/teaching/tasks/${encodeURIComponent(taskId)}/feedback`;
      return requestJson<TeachingHumanFeedbackResponse>(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
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

    /** Lists only agents visible to the backend-resolved session subject. */
    getAgentRegistry(): Promise<AgentRegistryResponse> {
      return requestJson<AgentRegistryResponse>("/api/agents/registry");
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
     * Executes a planned AI agent run under the authenticated backend subject.
     */
    async executeAgentRun(request: AgentRunExecuteRequest): Promise<AgentRunExecuteResponse> {
      const body = JSON.stringify(request);
      const path = "/api/agents/execute";
      return requestJson<AgentRunExecuteResponse>(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    /**
     * Compatibility submitter for callers that still use the synchronous writing method.
     *
     * It deliberately enters the same durable teaching-task endpoint as the asynchronous panel path, so choosing a
     * different client method cannot recreate the retired `/api/agents/writing` business workflow.
     */
    async runMultiAgentWriting(request: MultiAgentWritingRequest): Promise<MultiAgentWritingResponse> {
      const teachingRequest = await toTeachingHandoutRequest(request);
      const task = await requestJson<TeachingTaskResponse>("/api/teaching/tasks", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(teachingRequest),
      });
      return projectTeachingTaskAsWritingWorkflow(task);
    },

    /**
     * Starts the sole durable teaching-task workflow. workflowId remains a UI compatibility alias for taskId.
     */
    async startAsyncMultiAgentWriting(request: MultiAgentWritingRequest): Promise<MultiAgentWritingResponse> {
      const teachingRequest = await toTeachingHandoutRequest(request);
      const task = await requestJson<TeachingTaskResponse>("/api/teaching/tasks", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(teachingRequest),
      });
      return projectTeachingTaskAsWritingWorkflow(task);
    },

    /**
     * Resumes a failed multi-agent writing workflow from the first missing stage.
     */
    async resumeMultiAgentWriting(
      workflowId: string,
      _request: MultiAgentWritingRequest,
    ): Promise<MultiAgentWritingResponse> {
      const encodedWorkflowId = encodeURIComponent(workflowId);
      const task = await requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodedWorkflowId}/resume`, {
        method: "POST",
      });
      return projectTeachingTaskAsWritingWorkflow(task);
    },

    /**
     * Reads the latest safe multi-agent writing workflow status by workflow id.
     */
    async getMultiAgentWritingWorkflow(workflowId: string): Promise<MultiAgentWritingResponse> {
      const task = await requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodeURIComponent(workflowId)}`);
      return projectTeachingTaskAsWritingWorkflow(task);
    },

    /**
     * Reads safe ordered traces for a multi-agent writing workflow.
     */
    async getMultiAgentWritingTraces(workflowId: string): Promise<MultiAgentWritingTraceResponse> {
      const task = await requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodeURIComponent(workflowId)}`);
      return projectTeachingTaskAsWritingTraces(task);
    },

    /**
     * Reads owner-visible generated content for review and frontend preview.
     */
    async getMultiAgentWritingArtifact(workflowId: string): Promise<MultiAgentWritingArtifact> {
      const task = await requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodeURIComponent(workflowId)}`);
      return projectTeachingTaskAsWritingArtifact(task);
    },

    /**
     * Exports generated writing content as Markdown, LaTeX, or ZIP.
     */
    async exportMultiAgentWritingArtifact(
      workflowId: string,
      format: "markdown" | "latex" | "pdf" | "pdf-teacher" | "pdf-student" | "pdf-lecture" | "zip",
      layout: { headerText?: string; footerText?: string } = {},
    ): Promise<MultiAgentWritingArtifactExportResponse> {
      if (layout.headerText?.trim() || layout.footerText?.trim()) {
        throw new Error("Teaching task page chrome is fixed when the task is created");
      }
      const encodedTaskId = encodeURIComponent(workflowId);
      if (format === "zip") {
        const batch = await requestJson<TeachingHandoutBatchExportResponse>("/api/teaching/handouts/batch/zip", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ taskIds: [workflowId] }),
        });
        const bytes = await requestBytes(`/api/teaching/handouts/batch/zip/${encodeURIComponent(batch.batchId)}/download`);
        return directTeachingExport(workflowId, format, `${workflowId}-handouts.zip`, "application/zip", bytes);
      }
      if (format === "markdown") {
        const task = await requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodedTaskId}`);
        const bytes = new TextEncoder().encode(task.aiDraft?.content || task.handoutLatex);
        return directTeachingExport(workflowId, format, `${workflowId}-teacher.md`, "text/markdown; charset=UTF-8", bytes);
      }
      const version: TeachingHandoutVersion = format === "pdf-student"
        ? "student"
        : format === "pdf-lecture"
          ? "lecture"
          : "teacher";
      if (format === "latex") {
        const latex = await requestText(`/api/teaching/tasks/${encodedTaskId}/handout/${version}/latex`);
        return directTeachingExport(workflowId, format, `${workflowId}-${version}.tex`, "application/x-tex; charset=UTF-8", new TextEncoder().encode(latex));
      }
      const bytes = await requestBytes(`/api/teaching/tasks/${encodedTaskId}/handout/${version}/pdf`);
      return directTeachingExport(workflowId, format, `${workflowId}-${version}.pdf`, "application/pdf", bytes);
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

    explainStudentWeakPoint(request: TargetedStudentExplanationRequest): Promise<StudentExplanationResponse> {
      return requestJson<StudentExplanationResponse>("/api/students/learning/explanations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    recordStudentLearningAttempt(request: StudentLearningAttemptRequest): Promise<StudentLearningAttemptResponse> {
      return requestJson<StudentLearningAttemptResponse>("/api/students/learning/attempts", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    getStudentMastery(): Promise<StudentKnowledgeMasteryResponse[]> {
      return requestJson<StudentKnowledgeMasteryResponse[]>("/api/students/learning/mastery");
    },

    getStudentLearningRecommendations(limit = 10): Promise<StudentLearningRecommendationResponse[]> {
      return requestJson<StudentLearningRecommendationResponse[]>(
        `/api/students/learning/recommendations?limit=${encodeURIComponent(String(limit))}`,
      );
    },

    getStudentLearningPath(): Promise<StudentLearningPathResponse> {
      return requestJson<StudentLearningPathResponse>("/api/students/learning/path");
    },

    recognizeStudentLearningIntent(message: string): Promise<StudentLearningIntentResponse> {
      return requestJson<StudentLearningIntentResponse>("/api/students/learning/intent", {
        method: "POST",
        body: JSON.stringify({ message }),
      });
    },

    getTeacherLearningWeakPoints(studentId?: string): Promise<StudentKnowledgeMasteryResponse[]> {
      const suffix = studentId ? `?studentId=${encodeURIComponent(studentId)}` : "";
      return requestJson<StudentKnowledgeMasteryResponse[]>(`/api/teachers/learning/weak-points${suffix}`);
    },

    async submitTargetedLearningHandout(request: TargetedLearningHandoutRequest): Promise<TeachingTaskResponse> {
      const body = JSON.stringify(request);
      return requestJson<TeachingTaskResponse>("/api/teachers/learning/handout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    submitTargetedPractice(request: TargetedPracticeRequest): Promise<StudentPracticeTaskResponse> {
      return requestJson<StudentPracticeTaskResponse>("/api/students/learning/practice", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    getTargetedPractice(taskId: string): Promise<StudentPracticeTaskResponse> {
      return requestJson<StudentPracticeTaskResponse>(
        `/api/students/learning/practice/${encodeURIComponent(taskId)}`,
      );
    },

    async streamStudentQuestion(
      request: StudentExplanationRequest,
      onEvent: (eventName: string, payload: StudentExplanationStreamEvent) => void,
    ): Promise<StudentExplanationResponse> {
      let finalResponse: StudentExplanationResponse | null = null;
      await requestEventStream<StudentExplanationStreamEvent>(
        "/api/students/explanations/stream",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
        },
        (eventName, payload) => {
          onEvent(eventName, payload);
          if (payload.response) {
            finalResponse = payload.response;
          }
          if (payload.eventType === "error") {
            const suffix = payload.errorTraceId ? `（traceId: ${payload.errorTraceId}）` : "";
            throw new Error((payload.message || "流式讲解失败。") + suffix);
          }
        },
      );
      if (!finalResponse) {
        throw new Error("流式讲解已结束，但没有收到最终结果。");
      }
      return finalResponse;
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

    listStudentExplanationConversations(limit = 20): Promise<StudentExplanationConversationListResponse> {
      return requestJson<StudentExplanationConversationListResponse>(
        `/api/students/explanations/conversations?limit=${encodeURIComponent(String(limit))}`,
      );
    },

    getStudentExplanationConversation(conversationId: string, limit = 30): Promise<StudentExplanationConversationResponse> {
      return requestJson<StudentExplanationConversationResponse>(
        `/api/students/explanations/conversations/${encodeURIComponent(conversationId)}?limit=${encodeURIComponent(String(limit))}`,
      );
    },

    async refreshStudentDashboard(studentId?: string): Promise<StudentDashboardResponse> {
      const params = new URLSearchParams();
      if (studentId) {
        params.set("studentId", studentId);
      }
      const suffix = params.size > 0 ? `?${params.toString()}` : "";
      const path = `/api/students/dashboard/refresh${suffix}`;
      return requestJson<StudentDashboardResponse>(path, { method: "POST" });
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
      return requestJson<KnowledgePointResponse>("/api/knowledge/points", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    async createQuestionBankItem(request: QuestionBankItemCreateRequest): Promise<QuestionBankItemResponse> {
      const body = JSON.stringify(request);
      return requestJson<QuestionBankItemResponse>("/api/question-bank/items", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
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
      return requestJson<TeacherBlockQuestionImportResponse>(path, { method: "POST" });
    },

    /**
     * 读取当前教师可见的资料源列表。
     */
    listTeacherResources(): Promise<TeacherResourceDocumentResponse[]> {
      return requestJson<TeacherResourceDocumentResponse[]>("/api/teacher/resources");
    },

    /** Reads one visible document's parsed blocks through the backend session; callers must select the cited block. */
    listTeacherResourceBlocks(documentId: string): Promise<TeacherDocumentBlockResponse[]> {
      return requestJson<TeacherDocumentBlockResponse[]>(
        `/api/teacher/resources/${encodeURIComponent(documentId)}/blocks`,
      );
    },

    searchTeacherResourceBlocks(
      query: string,
      limit = 10,
      options?: TeacherResourceSearchOptions,
    ): Promise<TeacherResourceBlockSearchResponse> {
      /*
       * Keep teacher search query serialization on encodeURIComponent rather than URLSearchParams so spaces stay `%20`.
       * Existing audit snapshots and UI tests compare the exact request URL, and changing it to `+` would create noisy
       * diff/debug churn without any backend value.
       */
      const params = [
        `query=${encodeURIComponent(query)}`,
        `limit=${encodeURIComponent(String(limit))}`,
      ];
      for (const permissionScope of options?.permissionScopes ?? []) {
        params.push(`permissionScope=${encodeURIComponent(permissionScope)}`);
      }
      for (const documentId of options?.documentIds ?? []) {
        params.push(`documentId=${encodeURIComponent(documentId)}`);
      }
      for (const library of options?.libraries ?? []) {
        params.push(`library=${encodeURIComponent(library)}`);
      }
      for (const tag of options?.tags ?? []) {
        params.push(`tag=${encodeURIComponent(tag)}`);
      }
      const path = `/api/teacher/resources/search?${params.join("&")}`;
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

    /** Reads the current backend subject's durable Feishu OAuth binding through the authenticated transport. */
    getFeishuOAuthStatus(): Promise<{ status: "AUTHORIZED" | "BOT_AUTHORIZED" | "AUTH_REQUIRED"; expiresAt: string | null }> {
      return requestJson<{ status: "AUTHORIZED" | "BOT_AUTHORIZED" | "AUTH_REQUIRED"; expiresAt: string | null }>(
        "/api/feishu/oauth/status",
      );
    },

    /** Creates a fresh OAuth URL immediately before navigation so its short-lived state cannot become stale. */
    getFeishuOAuthAuthorizationUrl(): Promise<{ authorizationUrl: string }> {
      return requestJson<{ authorizationUrl: string }>("/api/feishu/oauth/authorize");
    },

    /**
     * 登记教师资料源，后端会返回预览和等待重建索引状态。
     */
    registerTeacherResource(
      request: TeacherResourceRegistrationRequest,
    ): Promise<TeacherResourceDocumentResponse> {
      /*
       * The backend now owns teacher-resource naming so uploads, local paths, and Feishu links all converge on the
       * same server-side title resolver. Keep the browser request thin and do not mirror that fallback logic here.
       */
      const normalizedRequest = request.sourceType === "feishu"
        ? { ...request, feishuExportFormat: request.feishuExportFormat ?? "md", parseMode: request.parseMode ?? "TEXT" }
        : { ...request, parseMode: request.parseMode ?? "TEXT" };
      const body = JSON.stringify(normalizedRequest);
      return requestJson<TeacherResourceDocumentResponse>("/api/teacher/resources", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
    },

    /**
     * Uploads teacher-owned files into the backend-managed staging root, then registers that stored directory through
     * the same teacher resource pipeline that handles local_path sync, parsing, vector rebuild, and permission scope.
     */
    async uploadTeacherResource(
      request: TeacherResourceUploadRequest,
    ): Promise<TeacherResourceDocumentResponse> {
      const normalizedSourceType = request.sourceType && request.sourceType !== "feishu"
        ? request.sourceType
        : "local_path";
      const normalizedParseMode = request.parseMode ?? "TEXT";
      const normalizedTitle = request.title?.trim() ?? "";
      const uploadPath = "/api/teacher/resources/upload";
      const formData = new FormData();
      formData.append("sourceType", normalizedSourceType);
      if (normalizedTitle) {
        formData.append("title", normalizedTitle);
      }
      formData.append("permissionScope", request.permissionScope);
      formData.append("parseMode", normalizedParseMode);
      for (const file of request.files) {
        const relativeName = file.webkitRelativePath && file.webkitRelativePath.trim().length > 0
          ? file.webkitRelativePath
          : file.name;
        formData.append("files", file, relativeName);
      }
      return requestFormJson<TeacherResourceDocumentResponse>(uploadPath, formData, { method: "POST" });
    },

    /**
     * 归档教师资料源，避免硬删除导致旧讲解引用断裂。
     */
    async archiveTeacherResource(documentId: string): Promise<TeacherResourceDocumentResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}`;
      return requestJson<TeacherResourceDocumentResponse>(path, { method: "DELETE" });
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
      return requestJson<TeacherSourceSyncJobResponse>(path, { method: "POST" });
    },

    async executeTeacherResourceSyncJob(
      documentId: string,
      jobId: string,
    ): Promise<TeacherSourceSyncJobResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs/${encodeURIComponent(jobId)}/execute`;
      return requestJson<TeacherSourceSyncJobResponse>(path, { method: "POST" });
    },

    async resumeTeacherResourceSyncJob(
      documentId: string,
      jobId: string,
    ): Promise<TeacherSourceSyncJobResponse> {
      const path = `/api/teacher/resources/${encodeURIComponent(documentId)}/sync-jobs/${encodeURIComponent(jobId)}/resume`;
      return requestJson<TeacherSourceSyncJobResponse>(path, { method: "POST" });
    },

    async rebuildTeacherResourceVectorIndex(documentId: string): Promise<VectorIndexRebuildResponse> {
      const path = `/api/vector-index/teacher-resources/${encodeURIComponent(documentId)}/rebuild`;
      return requestJson<VectorIndexRebuildResponse>(path, { method: "POST" });
    },
  };
}
