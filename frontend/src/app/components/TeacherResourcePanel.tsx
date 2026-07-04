import { FormEvent } from "react";
import { AlertCircle, Database, Loader2, Search } from "lucide-react";
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
import { countJsonArray, PanelTitle, StatusLine } from "./panelShared";

export function TeacherResourcePanel({
  resources,
  title,
  location,
  sourceType,
  scope,
  feishuExportFormat,
  loading,
  registering,
  searchingBlocks,
  syncingResourceId,
  importingResourceId,
  rebuildingResourceId,
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
  onTitleChange,
  onLocationChange,
  onSourceTypeChange,
  onScopeChange,
  onFeishuExportFormatChange,
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
  title: string;
  location: string;
  sourceType: string;
  scope: string;
  feishuExportFormat: "md" | "docx" | "pdf";
  loading: boolean;
  registering: boolean;
  searchingBlocks: boolean;
  syncingResourceId: string;
  importingResourceId: string;
  rebuildingResourceId: string;
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
  onTitleChange: (value: string) => void;
  onLocationChange: (value: string) => void;
  onSourceTypeChange: (value: string) => void;
  onScopeChange: (value: string) => void;
  onFeishuExportFormatChange: (value: "md" | "docx" | "pdf") => void;
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
      <PanelTitle icon={<Database size={18} />} title="Teacher resources" />
      <details className="resource-hint">
        <summary>Feishu setup</summary>
        <div className="resource-hint-body">
          <p>Enter a real Feishu folder or document URL. Use List/Search before registering when the folder is large.</p>
          <p>APPKEY stays on the backend. Download checkpoints are stored in MySQL and paused jobs can Resume.</p>
        </div>
      </details>
      <form className="search-form" onSubmit={onRegister}>
        <label>
          <span>Title</span>
          <input
            value={title}
            onChange={(event) => onTitleChange(event.target.value)}
            placeholder="High school function handout / Feishu folder"
          />
        </label>
        <label>
          <span>Source</span>
          <select value={sourceType} onChange={(event) => onSourceTypeChange(event.target.value)}>
            <option value="local_path">Local path</option>
            <option value="feishu">Feishu URL</option>
          </select>
        </label>
        <label>
          <span>{sourceType === "feishu" ? "Feishu URL" : "Local path"}</span>
          <input
            value={location}
            onChange={(event) => onLocationChange(event.target.value)}
            placeholder={sourceType === "feishu" ? "https://..." : "C:\\path\\to\\file.pdf"}
          />
        </label>
        {sourceType === "feishu" ? (
          <label>
            <span>Export</span>
            <select
              value={feishuExportFormat}
              onChange={(event) => onFeishuExportFormatChange(event.target.value as "md" | "docx" | "pdf")}
            >
              <option value="md">MD</option>
              <option value="docx">DOCX</option>
              <option value="pdf">PDF</option>
            </select>
          </label>
        ) : null}
        <label>
          <span>Scope</span>
          <select value={scope} onChange={(event) => onScopeChange(event.target.value)}>
            <option value="TEACHER_PRIVATE">Teacher private</option>
            <option value="MATH_VIP">Math VIP</option>
            <option value="PUBLIC_TEXTBOOK">Public textbook</option>
          </select>
        </label>
        <button type="submit" disabled={registering}>
          {registering ? <Loader2 className="spin" size={17} /> : <Database size={17} />}
          <span>Register</span>
        </button>
      </form>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="Loading teacher resources" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {importResult ? (
        <StatusLine
          icon={<Database size={16} />}
          text={`Imported ${importResult.importedQuestionCount}, skipped ${importResult.skippedBlockCount}, duplicate ${importResult.duplicateBlockCount}`}
        />
      ) : null}
      {indexRebuildResult ? (
        <StatusLine
          icon={<Database size={16} />}
          text={`${indexRebuildResult.status}: ${indexRebuildResult.message}; embedded ${indexRebuildResult.embeddedCount}/${indexRebuildResult.blockCount}, upserted ${indexRebuildResult.upsertedCount}`}
        />
      ) : null}
      <div className="feishu-discovery-panel">
        <label>
          <span>Feishu search</span>
          <input
            value={feishuDiscoveryQuery}
            onChange={(event) => onFeishuDiscoveryQueryChange(event.target.value)}
            placeholder="Keyword, such as space vector"
          />
        </label>
        <div className="feishu-discovery-actions">
          <button type="button" onClick={() => onDiscoverFeishu("list")} disabled={discoveringFeishu}>
            {discoveringFeishu ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>List</span>
          </button>
          <button type="button" onClick={() => onDiscoverFeishu("search")} disabled={discoveringFeishu}>
            {discoveringFeishu ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            <span>Search</span>
          </button>
        </div>
        {feishuDiscoveryResult ? (
          <div className="feishu-candidate-list">
            <div className="resource-search-summary">
              <span>{feishuDiscoveryResult.mode}</span>
              <span>{feishuDiscoveryResult.candidateCount} candidates</span>
              <span>{feishuDiscoveryResult.rootUrl}</span>
            </div>
            {feishuDiscoveryResult.candidates.map((candidate) => (
              <article className="feishu-candidate" key={`${candidate.resourceType}:${candidate.token}:${candidate.url}`}>
                <div>
                  <strong>{candidate.name}</strong>
                  <span>
                    {candidate.resourceType} / {candidate.path} / depth {candidate.depth}
                  </span>
                </div>
                <button type="button" onClick={() => onUseFeishuCandidate(candidate)} disabled={!candidate.downloadable}>
                  Use
                </button>
              </article>
            ))}
          </div>
        ) : null}
      </div>
      <form className="resource-block-search" onSubmit={onBlockSearch}>
        <label>
          <span>Block search</span>
          <input
            value={blockSearchQuery}
            onChange={(event) => onBlockSearchQueryChange(event.target.value)}
            placeholder="Knowledge point, method, formula, or question keyword"
          />
        </label>
        <button type="submit" disabled={searchingBlocks}>
          {searchingBlocks ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>Search</span>
        </button>
      </form>
      {blockSearchResult ? (
        <div className="resource-search-results">
          <div className="resource-search-summary">
            <span>{blockSearchResult.retrievalMode}</span>
            <span>{blockSearchResult.hitCount} hits</span>
            <span>{blockSearchResult.queryId}</span>
          </div>
          {blockSearchAudit ? (
            <div className="resource-audit-summary">
              <span>{blockSearchAudit.endpoint}</span>
              <span>{blockSearchAudit.elapsedMs} ms</span>
              <span>
                {blockSearchAudit.subjectType}:{blockSearchAudit.subjectId}
              </span>
            </div>
          ) : null}
          {blockSearchResult.hits.map((hit) => (
            <article className="resource-search-hit" key={`${hit.documentId}:${hit.blockId}`}>
              <strong>{hit.documentTitle}</strong>
              <span>
                {hit.permissionScope} / {hit.blockType}
                {hit.pageNo ? ` / p.${hit.pageNo}` : ""}
              </span>
              <p>{hit.snippet}</p>
            </article>
          ))}
        </div>
      ) : null}
      <div className="resource-list">
        {!resources.length ? <div className="empty-state compact">No registered teacher resources.</div> : null}
        {resources.map((resource) => {
          const latestJob = syncJobsByDocument[resource.documentId]?.[0];
          const latestCheckpoint = latestJob ? syncCheckpointsByJob[latestJob.jobId] : undefined;
          return (
            <article className="resource-item" key={resource.documentId}>
              <div>
                <strong>{resource.title}</strong>
                <span>
                  {resource.sourceType} / {resource.permissionScope}
                  {resource.sourceType === "feishu" ? ` / ${resource.feishuExportFormat ?? "md"}` : ""}
                </span>
              </div>
              <div className="resource-status">
                <span>sync: {latestJob?.status ?? resource.syncStatus}</span>
                <span>parse: {resource.parseStatus ?? "unknown"}</span>
                <span>embed: {resource.embeddingStatus ?? "unknown"}</span>
                <span>index: {latestJob?.phase ?? resource.indexStatus ?? "waiting_rebuild"}</span>
              </div>
              {latestJob ? (
                <p>
                  {latestJob.operation}: {latestJob.message ?? latestJob.createdAt ?? latestJob.jobId}
                </p>
              ) : resource.previewFiles?.length ? (
                <p>{resource.previewFiles.map((file) => file.fileName).join(", ")}</p>
              ) : null}
              {latestCheckpoint ? <SyncCheckpointView checkpoint={latestCheckpoint} /> : null}
              <button
                type="button"
                onClick={() => onSync(resource.documentId)}
                disabled={syncingResourceId === resource.documentId}
              >
                {syncingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                <span>Sync</span>
              </button>
              {latestJob?.status === "paused" ? (
                <button
                  type="button"
                  onClick={() => onResume(resource.documentId, latestJob.jobId)}
                  disabled={syncingResourceId === resource.documentId}
                >
                  {syncingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                  <span>Resume</span>
                </button>
              ) : null}
              <button
                type="button"
                onClick={() => onImportQuestions(resource.documentId)}
                disabled={importingResourceId === resource.documentId}
              >
                {importingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                <span>Import questions</span>
              </button>
              <button
                type="button"
                onClick={() => onRebuildIndex(resource.documentId)}
                disabled={rebuildingResourceId === resource.documentId}
              >
                {rebuildingResourceId === resource.documentId ? (
                  <Loader2 className="spin" size={15} />
                ) : (
                  <Database size={15} />
                )}
                <span>Rebuild index</span>
              </button>
              <button type="button" onClick={() => onArchive(resource.documentId)}>
                Archive
              </button>
            </article>
          );
        })}
      </div>
    </section>
  );
}

export function SyncCheckpointView({ checkpoint }: { checkpoint: TeacherSourceSyncCheckpointResponse }) {
  return (
    <div className="sync-checkpoint">
      <div>
        <span>Checkpoint</span>
        <strong>{checkpoint.currentPath || checkpoint.currentFolderToken}</strong>
      </div>
      <div className="sync-checkpoint-grid">
        <span>{checkpoint.pageToken ? `cursor ${checkpoint.pageToken}` : "cursor none"}</span>
        <span>{countJsonArray(checkpoint.downloadedItemsJson)} downloaded</span>
        <span>{countJsonArray(checkpoint.failedItemsJson)} failed</span>
        <span>v{checkpoint.cursorVersion}</span>
      </div>
    </div>
  );
}
