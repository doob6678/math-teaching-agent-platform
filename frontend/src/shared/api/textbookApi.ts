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
  /** 失败原因。 */
  errorMessage?: string;
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Pick<Response, "ok" | "status" | "json" | "text">>;

const LOCAL_CONSOLE_HEADERS = {
  "X-Tenant-Id": "default",
  "X-Subject-Type": "teacher",
  "X-Subject-Id": "local-teacher-console",
  "X-Device-Id": "local-browser-console",
};

export function createTextbookApiClient(baseUrl: string, fetchImpl: FetchLike = fetch) {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, "");

  /**
   * 请求后端 JSON，并携带本地教师控制台身份头用于接口分级和限流审计。
   */
  async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetchImpl(`${normalizedBaseUrl}${path}`, {
      ...init,
      headers: LOCAL_CONSOLE_HEADERS,
      ...("headers" in init ? { headers: { ...LOCAL_CONSOLE_HEADERS, ...init.headers } } : {}),
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Backend request failed: ${response.status} ${body}`.trim());
    }
    return response.json() as Promise<T>;
  }

  return {
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
     * 提交可恢复教学任务。前端需要保存返回的 taskId，页面离开后可继续查询。
     */
    submitTeachingTask(request: TeachingTaskRequest): Promise<TeachingTaskResponse> {
      return requestJson<TeachingTaskResponse>("/api/teaching/tasks", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
    },

    /**
     * 按 taskId 读取教学任务结果，用于页面恢复和轮询。
     */
    getTeachingTask(taskId: string): Promise<TeachingTaskResponse> {
      return requestJson<TeachingTaskResponse>(`/api/teaching/tasks/${encodeURIComponent(taskId)}`);
    },
  };
}
