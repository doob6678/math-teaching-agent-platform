import { FormEvent } from "react";
import { AlertCircle, Database, Loader2, Search, X } from "lucide-react";
import {
  TeacherBlockQuestionImportResponse,
  TeacherFeishuDiscoveryCandidate,
  TeacherFeishuDiscoveryResponse,
  TeacherResourceBlockSearchAuditEvent,
  TeacherResourceBlockSearchResponse,
  TeacherResourceDocumentResponse,
  TeacherSourceSyncCheckpointResponse,
  TeacherSourceSyncJobResponse,
  VectorIndexRebuildResponse,
} from "../../shared/api/textbookApi";
import { compactText, countJsonArray, PanelTitle, StatusLine } from "./panelShared";

const COMPLETED_SYNC_STATUSES = new Set(["synced", "completed"]);
const COMPLETED_PARSE_STATUSES = new Set(["parsed", "completed"]);
const COMPLETED_INDEX_STATUSES = new Set(["ready", "completed"]);

export function TeacherResourcePanel({
  resources,
  location,
  files,
  sourceType,
  scope,
  feishuExportFormat,
  parseMode,
  loading,
  registering,
  searchingBlocks,
  syncingResourceId,
  importingResourceId,
  rebuildingResourceId,
  deletingResourceId,
  importResult,
  indexRebuildResult,
  syncJobsByDocument,
  syncCheckpointsByJob,
  blockSearchQuery,
  blockSearchResult,
  blockSearchAudit,
  feishuDiscoveryQuery,
  feishuDiscoveryResult,
  discoveringFeishu,
  error,
  onLocationChange,
  onFilesChange,
  onSourceTypeChange,
  onScopeChange,
  onFeishuExportFormatChange,
  onParseModeChange,
  onBlockSearchQueryChange,
  onBlockSearch,
  onFeishuDiscoveryQueryChange,
  onDiscoverFeishu,
  onUseFeishuCandidate,
  onRegister,
  onArchive,
  onSync,
  onResume,
  onImportQuestions,
  onRebuildIndex,
}: {
  resources: TeacherResourceDocumentResponse[];
  location: string;
  files: File[];
  sourceType: string;
  scope: string;
  feishuExportFormat: "md" | "docx" | "pdf";
  parseMode: "TEXT" | "AI";
  loading: boolean;
  registering: boolean;
  searchingBlocks: boolean;
  syncingResourceId: string;
  importingResourceId: string;
  rebuildingResourceId: string;
  deletingResourceId: string;
  importResult: TeacherBlockQuestionImportResponse | null;
  indexRebuildResult: VectorIndexRebuildResponse | null;
  syncJobsByDocument: Record<string, TeacherSourceSyncJobResponse[]>;
  syncCheckpointsByJob: Record<string, TeacherSourceSyncCheckpointResponse>;
  blockSearchQuery: string;
  blockSearchResult: TeacherResourceBlockSearchResponse | null;
  blockSearchAudit: TeacherResourceBlockSearchAuditEvent | null;
  feishuDiscoveryQuery: string;
  feishuDiscoveryResult: TeacherFeishuDiscoveryResponse | null;
  discoveringFeishu: boolean;
  error: string;
  onLocationChange: (value: string) => void;
  onFilesChange: (files: FileList | null) => void;
  onSourceTypeChange: (value: string) => void;
  onScopeChange: (value: string) => void;
  onFeishuExportFormatChange: (value: "md" | "docx" | "pdf") => void;
  onParseModeChange: (value: "TEXT" | "AI") => void;
  onBlockSearchQueryChange: (value: string) => void;
  onBlockSearch: (event: FormEvent<HTMLFormElement>) => void;
  onFeishuDiscoveryQueryChange: (value: string) => void;
  onDiscoverFeishu: (mode: "list" | "search") => void;
  onUseFeishuCandidate: (candidate: TeacherFeishuDiscoveryCandidate) => void;
  onRegister: (event: FormEvent<HTMLFormElement>) => void;
  onArchive: (documentId: string) => void;
  onSync: (documentId: string) => void;
  onResume: (documentId: string, jobId: string) => void;
  onImportQuestions: (documentId: string) => void;
  onRebuildIndex: (documentId: string) => void;
}) {
  return (
    <section className="teacher-resource-panel">
      <PanelTitle icon={<Database size={18} />} title="教师资源" />

      <details className="resource-hint">
        <summary>飞书接入说明</summary>
        <div className="resource-hint-body">
          <p>粘贴真实飞书文件夹或文档链接即可。大目录先用“列出”或“搜索”，再选择具体节点入库。</p>
          <p>密钥保存在后端，下载断点和同步进度落库到 MySQL，网络波动后可恢复。</p>
          <p>本地上传会先落到后端 staging 目录，再复用原有同步、切块、向量和权限链路，不会分叉出第二套入库系统。</p>
        </div>
      </details>

      <form className="search-form" onSubmit={onRegister}>
        <label>
          <span>来源类型</span>
          <select className="form-select" value={sourceType} onChange={(event) => onSourceTypeChange(event.target.value)}>
            <option value="feishu">飞书链接</option>
            <option value="teacher_resource">教师资料上传</option>
            <option value="qq_bundle">QQ 专题包上传</option>
            <option value="gaokao">高考真题上传</option>
            <option value="mock_exam">模拟题上传</option>
            <option value="local_path">服务器本地路径</option>
          </select>
        </label>
        {sourceType === "feishu" ? (
          <label>
            <span>飞书链接</span>
            <input
              className="form-input"
              value={location}
              onChange={(event) => onLocationChange(event.target.value)}
              placeholder="https://..."
            />
          </label>
        ) : (
          <>
            <label>
              <span>上传文件</span>
              <input
                className="form-input"
                type="file"
                multiple
                onChange={(event) => onFilesChange(event.target.files)}
              />
            </label>
            <label>
              <span>上传文件夹</span>
              <input
                className="form-input"
                type="file"
                multiple
                ref={(node) => {
                  if (!node) return;
                  node.setAttribute("webkitdirectory", "");
                  node.setAttribute("directory", "");
                }}
                onChange={(event) => onFilesChange(event.target.files)}
              />
            </label>
            <label>
              <span>服务器本地路径（可选）</span>
              <input
                className="form-input"
                value={location}
                onChange={(event) => onLocationChange(event.target.value)}
                placeholder="C:\\path\\to\\resource-root"
              />
            </label>
            <div className="resource-search-summary">
              <span>{sourceTypeLabel(sourceType)}</span>
              <span>{files.length > 0 ? `已选择 ${files.length} 个文件` : "未选择浏览器上传文件"}</span>
            </div>
            {files.length > 0 ? (
              <div className="feishu-candidate-list">
                {files.slice(0, 6).map((file) => (
                  <article className="feishu-candidate" key={`${file.name}:${file.size}:${file.lastModified}`}>
                    <div>
                      <strong>{compactText(file.webkitRelativePath || file.name, 60)}</strong>
                      <span>{file.type || "application/octet-stream"} / {file.size} B</span>
                    </div>
                  </article>
                ))}
                {files.length > 6 ? <div className="empty-state compact">其余 {files.length - 6} 个文件已省略显示。</div> : null}
              </div>
            ) : null}
          </>
        )}
        {sourceType === "feishu" ? (
          <label>
            <span>导出格式</span>
            <select
              className="form-select"
              value={feishuExportFormat}
              onChange={(event) => onFeishuExportFormatChange(event.target.value as "md" | "docx" | "pdf")}
            >
              <option value="md">Markdown</option>
              <option value="docx">DOCX</option>
              <option value="pdf">PDF</option>
            </select>
          </label>
        ) : null}
        <label>
          <span>资源范围</span>
          <select className="form-select" value={scope} onChange={(event) => onScopeChange(event.target.value)}>
            <option value="TEACHER_PRIVATE">教师私有</option>
            <option value="MATH_VIP">教研共享</option>
            <option value="PUBLIC_TEXTBOOK">公开教材</option>
          </select>
        </label>
        <label>
          <span>解析模式</span>
          <select className="form-select" value={parseMode} onChange={(event) => onParseModeChange(event.target.value as "TEXT" | "AI")}>
            <option value="TEXT">TEXT：文字与结构提取</option>
            <option value="AI">AI：图文语义标注</option>
          </select>
        </label>
        <button className="btn btn-primary" type="submit" disabled={registering}>
          {registering ? <Loader2 className="spin" size={17} /> : <Database size={17} />}
          <span>登记资源</span>
        </button>
      </form>

      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在读取教师资源" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {importResult ? (
        <StatusLine
          icon={<Database size={16} />}
          text={`题目入库完成：新增 ${importResult.importedQuestionCount}，跳过 ${importResult.skippedBlockCount}，重复 ${importResult.duplicateBlockCount}`}
        />
      ) : null}
      {indexRebuildResult ? (
        <StatusLine
          icon={<Database size={16} />}
          text={`向量重建${statusLabel(indexRebuildResult.status)}：嵌入 ${indexRebuildResult.embeddedCount}/${indexRebuildResult.blockCount}，写入 ${indexRebuildResult.upsertedCount}`}
        />
      ) : null}

      <div className="feishu-discovery-panel">
        <label>
          <span>飞书查找</span>
          <input
            className="form-input"
            value={feishuDiscoveryQuery}
            onChange={(event) => onFeishuDiscoveryQueryChange(event.target.value)}
            placeholder="输入关键词，例如：空间向量"
          />
        </label>
        <div className="feishu-discovery-actions">
          <button className="btn btn-secondary" type="button" onClick={() => onDiscoverFeishu("list")} disabled={discoveringFeishu}>
            {discoveringFeishu ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>列出</span>
          </button>
          <button className="btn btn-secondary" type="button" onClick={() => onDiscoverFeishu("search")} disabled={discoveringFeishu}>
            {discoveringFeishu ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            <span>搜索</span>
          </button>
        </div>
        {feishuDiscoveryResult ? (
          <div className="feishu-candidate-list">
            <div className="resource-search-summary">
              <span>{feishuDiscoveryResult.mode === "list" ? "目录浏览" : "关键词搜索"}</span>
              <span>{feishuDiscoveryResult.candidateCount} 项</span>
            </div>
            {feishuDiscoveryResult.candidates.map((candidate) => (
              <article className="feishu-candidate" key={`${candidate.resourceType}:${candidate.token}:${candidate.url}`}>
                <div>
                  <strong>{candidate.name}</strong>
                  <span>{resourceTypeLabel(candidate.resourceType)} / {compactText(candidate.path, 44)}</span>
                </div>
                <button type="button" onClick={() => onUseFeishuCandidate(candidate)} disabled={!candidate.downloadable}>
                  选用
                </button>
              </article>
            ))}
          </div>
        ) : null}
      </div>

      <form className="resource-block-search" onSubmit={onBlockSearch}>
        <label>
          <span>资源检索</span>
          <input
            className="form-input"
            value={blockSearchQuery}
            onChange={(event) => onBlockSearchQueryChange(event.target.value)}
            placeholder="知识点、题型方法、公式或关键词"
          />
        </label>
        <button className="btn btn-primary" type="submit" disabled={searchingBlocks}>
          {searchingBlocks ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>检索</span>
        </button>
      </form>

      {blockSearchResult ? (
        <div className="resource-search-results">
          <div className="resource-search-summary">
            <span>{retrievalModeLabel(blockSearchResult.retrievalMode)}</span>
            <span>{blockSearchResult.hitCount} 条命中</span>
            {blockSearchAudit ? <span>{blockSearchAudit.elapsedMs} ms</span> : null}
          </div>
          {blockSearchResult.hits.map((hit) => (
            <article className="resource-search-hit" key={`${hit.documentId}:${hit.blockId}`}>
              <strong>{hit.documentTitle}</strong>
              <span>
                {scopeLabel(hit.permissionScope)} / {sourceTypeLabel(hit.sourceType || "teacher_resource")} / {blockTypeLabel(hit.blockType)}
                {hit.pageNo ? ` / 第 ${hit.pageNo} 页` : ""}
                {hit.blockRole ? ` / ${blockRoleLabel(hit.blockRole)}` : ""}
              </span>
              <p>{compactText(hit.snippet, 120)}</p>
              {hit.sourcePath ? <p>{compactText(hit.sourcePath, 120)}</p> : null}
              {hit.assetRefs?.length ? (
                <div className="resource-search-summary">
                  <span>{hit.assetRefs.length} 个受控图片/附件</span>
                  <span>{hit.imageAssetIds?.length ?? hit.assetRefs.length} 个 assetId</span>
                </div>
              ) : null}
              {hit.assetRefs?.length ? (
                <div className="feishu-candidate-list">
                  {hit.assetRefs.slice(0, 4).map((asset) => (
                    <article className="feishu-candidate" key={asset.assetId}>
                      <div>
                        <strong>{compactText(asset.fileName || asset.sourcePath || asset.assetId, 56)}</strong>
                        <span>
                          {asset.mimeType || "application/octet-stream"}
                          {asset.pageNo ? ` / 第 ${asset.pageNo} 页` : ""}
                        </span>
                      </div>
                      <a href={asset.assetUri} target="_blank" rel="noreferrer">
                        查看
                      </a>
                    </article>
                  ))}
                </div>
              ) : null}
              <details className="review-details">
                <summary>查看片段</summary>
                <p>{hit.snippet}</p>
                {hit.evidenceText && hit.evidenceText !== hit.snippet ? <p>{hit.evidenceText}</p> : null}
              </details>
            </article>
          ))}
        </div>
      ) : null}

      <div className="resource-list">
        {!resources.length ? <div className="empty-state compact">当前没有已登记的教师资源。</div> : null}
        {resources.map((resource) => {
          const latestJob = syncJobsByDocument[resource.documentId]?.[0];
          const latestCheckpoint = latestJob ? syncCheckpointsByJob[latestJob.jobId] : undefined;
          const readyForQuestionImport = isTeacherResourceReady(resource);
          return (
            <article className="resource-item" key={resource.documentId}>
              <div>
                <strong>{resource.title}</strong>
                <span>
                  {sourceTypeLabel(resource.sourceType)} / {scopeLabel(resource.permissionScope)}
                  {resource.sourceType === "feishu" ? ` / ${exportFormatLabel(resource.feishuExportFormat)}` : ""}
                  {` / ${parseModeLabel(resource.parseMode)}`}
                </span>
              </div>
              <div className="resource-status">
                <span>同步 {statusLabel(latestJob?.status ?? resource.syncStatus)}</span>
                {latestJob?.failure?.providerCode ? <span>飞书错误 {latestJob.failure.providerCode}</span> : null}
                <span>解析 {statusLabel(resource.parseStatus)}</span>
                <span>向量 {statusLabel(resource.embeddingStatus)}</span>
                <span>索引 {statusLabel(resource.indexStatus ?? "waiting_rebuild")}</span>
              </div>
              {latestJob ? (
                <p>{compactText(syncJobMessage(latestJob.message) || latestJob.createdAt || latestJob.jobId, 120)}</p>
              ) : resource.previewFiles?.length ? (
                <p>{compactText(resource.previewFiles.map((file) => file.fileName).join("，"), 120)}</p>
              ) : null}
              {latestJob?.failure ? <SyncFailureView failure={latestJob.failure} /> : null}
              {latestCheckpoint ? <SyncCheckpointView checkpoint={latestCheckpoint} /> : null}
              <div className="resource-action-row">
                <button
                  className="btn btn-secondary btn-sm"
                  type="button"
                  onClick={() => onSync(resource.documentId)}
                  disabled={syncingResourceId === resource.documentId}
                >
                  {syncingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                  <span>同步</span>
                </button>
                {latestJob?.status === "paused" ? (
                  <button
                    className="btn btn-secondary btn-sm"
                    type="button"
                    onClick={() => onResume(resource.documentId, latestJob.jobId)}
                    disabled={syncingResourceId === resource.documentId}
                  >
                    {syncingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                    <span>恢复</span>
                  </button>
                ) : null}
                <button
                  className="btn btn-secondary btn-sm"
                  type="button"
                  onClick={() => onImportQuestions(resource.documentId)}
                  disabled={importingResourceId === resource.documentId || !readyForQuestionImport}
                  title={readyForQuestionImport ? "" : "资料需完成同步、解析和索引后才能入题库"}
                >
                  {importingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                  <span>入题库</span>
                </button>
                <button
                  className="btn btn-secondary btn-sm"
                  type="button"
                  onClick={() => onRebuildIndex(resource.documentId)}
                  disabled={rebuildingResourceId === resource.documentId}
                >
                  {rebuildingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                  <span>重建向量</span>
                </button>
                <button
                  className="btn btn-danger btn-sm"
                  type="button"
                  onClick={() => onArchive(resource.documentId)}
                  disabled={deletingResourceId === resource.documentId}
                >
                  {deletingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <X size={15} />}
                  <span>删除</span>
                </button>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

function statusLabel(value?: string | null) {
  const normalized = (value ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    completed: "已完成",
    complete: "已完成",
    success: "成功",
    succeeded: "成功",
    ready: "就绪",
    parsed: "已解析",
    indexed: "已索引",
    synced: "已同步",
    running: "运行中",
    processing: "处理中",
    pending: "等待中",
    created: "已创建",
    queued: "排队中",
    paused: "已暂停",
    failed: "失败",
    error: "异常",
    archived: "已归档",
    none: "未开始",
    unknown: "未识别",
    waiting_rebuild: "待重建",
    ready_to_index: "待索引",
    download_completed: "下载完成",
    parse_completed: "解析完成",
    embedding_completed: "向量完成",
    index_completed: "索引完成",
  };
  return labels[normalized] ?? (value ? value : "未开始");
}

function phaseLabel(value?: string | null) {
  const normalized = (value ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    download: "下载",
    parse: "解析",
    embedding: "向量化",
    index: "写入索引",
    completed: "已完成",
    failed: "失败",
    paused: "已暂停",
    waiting_rebuild: "待重建",
    ready_to_index: "待索引",
  };
  return labels[normalized] ?? statusLabel(value);
}

/** Mirrors the backend evidence gate so unavailable resources cannot be selected from this management surface. */
function isTeacherResourceReady(resource: TeacherResourceDocumentResponse) {
  return COMPLETED_SYNC_STATUSES.has(normalizedStatus(resource.syncStatus))
    && COMPLETED_PARSE_STATUSES.has(normalizedStatus(resource.parseStatus))
    && COMPLETED_INDEX_STATUSES.has(normalizedStatus(resource.indexStatus));
}

/** Normalizes durable backend status values before comparing them with the accepted readiness vocabulary. */
function normalizedStatus(value: string | undefined) {
  return (value || "").trim().toLowerCase();
}

function sourceTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    feishu: "飞书",
    teacher_resource: "教师资料",
    qq_bundle: "QQ 专题包",
    gaokao: "高考真题",
    mock_exam: "模拟题",
    local_path: "本地文件",
    local: "本地文件",
  };
  return labels[(value ?? "").trim().toLowerCase()] ?? (value || "未知来源");
}

function blockRoleLabel(value?: string | null) {
  const labels: Record<string, string> = {
    analysis: "解析",
    question: "题面",
    lesson: "讲解",
    method: "方法",
    boardwork: "板书",
    tip: "提示",
    reference: "参考",
  };
  return labels[(value ?? "").trim().toLowerCase()] ?? (value || "未标注角色");
}

function resourceTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    doc: "文档",
    docx: "文档",
    sheet: "表格",
    bitable: "多维表格",
    file: "文件",
    folder: "文件夹",
    wiki: "知识库",
  };
  return labels[(value ?? "").trim().toLowerCase()] ?? (value || "资源");
}

function scopeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    TEACHER_PRIVATE: "教师私有",
    MATH_VIP: "教研共享",
    PUBLIC_TEXTBOOK: "公开教材",
  };
  return labels[value ?? ""] ?? (value || "未设置范围");
}

function retrievalModeLabel(value?: string | null) {
  const normalized = (value ?? "").trim().toLowerCase();
  if (!normalized) {
    return "混合检索";
  }
  if (normalized.includes("hybrid")) {
    return "关键词与向量混合检索";
  }
  if (normalized.includes("vector")) {
    return "向量检索";
  }
  if (normalized.includes("keyword") || normalized.includes("bm25")) {
    return "关键词检索";
  }
  return value ?? "混合检索";
}

function blockTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    text: "正文",
    markdown: "正文",
    question: "题目",
    formula: "公式",
    table: "表格",
    image: "图片说明",
    heading: "标题",
  };
  return labels[(value ?? "").trim().toLowerCase()] ?? (value || "片段");
}

function exportFormatLabel(value?: string | null) {
  const labels: Record<string, string> = {
    md: "Markdown",
    markdown: "Markdown",
    docx: "Word",
    pdf: "PDF",
  };
  return labels[(value ?? "md").trim().toLowerCase()] ?? (value || "Markdown");
}

function parseModeLabel(value?: string | null) {
  return (value ?? "TEXT").toUpperCase() === "AI" ? "AI 解析" : "TEXT 解析";
}

function syncJobMessage(value?: string | null) {
  const text = (value ?? "").trim();
  if (!text) {
    return "";
  }
  const parsedMatch = text.match(/Parsed\s+(\d+)\s+blocks\s+from\s+local\s+source/i);
  const parts: string[] = [];
  if (parsedMatch) {
    parts.push(`已解析 ${parsedMatch[1]} 个文本块`);
  }
  if (/Vector index indexed:\s*Milvus upsert completed/i.test(text)) {
    parts.push("向量索引已写入 Milvus");
  }
  if (/download_completed/i.test(text)) {
    parts.push("下载完成");
  }
  if (/parse_completed/i.test(text)) {
    parts.push("解析完成");
  }
  if (/Downloading Feishu source files/i.test(text)) {
    parts.push("正在下载飞书资源");
  }
  if (/Parsing source files/i.test(text)) {
    parts.push("正在解析资源");
  }
  if (/AI labeling unavailable, kept TEXT extraction/i.test(text)) {
    parts.push("AI 标注未配置，已保留 TEXT 解析结果");
  }
  if (/ProxyError|tunnel connection reset|connection reset|timeout/i.test(text)) {
    parts.push("网络连接中断，可恢复后继续");
  }
  if (/completed/i.test(text) && !parts.length) {
    parts.push("任务已完成");
  }
  return parts.length ? parts.join("，") : "同步状态已更新";
}

/** Displays only provider-confirmed authorization details; no tenant app id or guessed grant URL is exposed. */
function SyncFailureView({
  failure,
}: {
  failure: NonNullable<TeacherSourceSyncJobResponse["failure"]>;
}) {
  if (!failure.providerCode && !failure.requiredScopes.length && !failure.authorizationUrl) {
    return null;
  }
  return (
    <div className="resource-sync-failure" role="alert">
      {failure.requiredScopes.length ? <span>需要权限：{failure.requiredScopes.join("，")}</span> : null}
      {failure.authorizationUrl ? (
        <a href={failure.authorizationUrl} target="_blank" rel="noreferrer">
          前往授权
        </a>
      ) : null}
    </div>
  );
}

export function SyncCheckpointView({ checkpoint }: { checkpoint: TeacherSourceSyncCheckpointResponse }) {
  return (
    <details className="sync-checkpoint">
      <summary>同步断点</summary>
      <div className="sync-checkpoint-grid">
        <span>{compactText(checkpoint.currentPath || "已保存断点位置", 48)}</span>
        <span>{countJsonArray(checkpoint.downloadedItemsJson)} 已下载</span>
        <span>{countJsonArray(checkpoint.failedItemsJson)} 失败</span>
        <span>{checkpoint.pageToken ? "有游标" : "无游标"}</span>
      </div>
    </details>
  );
}
