import { AlertCircle, BookOpen, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  RetrievalAuditDetail,
  TeachingTaskResponse,
  TextbookSearchHit,
  TextbookSearchResponse,
  TextbookSummary,
  createTextbookApiClient,
} from "../shared/api/textbookApi";

const DEFAULT_BACKEND_URL = import.meta.env.VITE_BACKEND_URL ?? "http://127.0.0.1:8080";
const TEACHING_TASK_STORAGE_KEY = "math-agent:last-teaching-task-id";

/**
 * 教材检索控制台。当前阶段面向教师端/后台资料搜索，用来验证 BM25-first 检索证据是否可审计。
 */
export function App() {
  const api = useMemo(() => createTextbookApiClient(DEFAULT_BACKEND_URL), []);
  const [summary, setSummary] = useState<TextbookSummary | null>(null);
  const [summaryError, setSummaryError] = useState("");
  const [query, setQuery] = useState("分段函数");
  const [limit, setLimit] = useState(5);
  const [searchResult, setSearchResult] = useState<TextbookSearchResponse | null>(null);
  const [auditDetail, setAuditDetail] = useState<RetrievalAuditDetail | null>(null);
  const [searchError, setSearchError] = useState("");
  const [auditError, setAuditError] = useState("");
  const [teachingQuestion, setTeachingQuestion] = useState("已知函数 f(x) 的定义域为 R，求 D(-1)");
  const [learningGoal, setLearningGoal] = useState("理解函数新概念综合题");
  const [teachingTask, setTeachingTask] = useState<TeachingTaskResponse | null>(null);
  const [teachingError, setTeachingError] = useState("");
  const [loadingSummary, setLoadingSummary] = useState(false);
  const [searching, setSearching] = useState(false);
  const [loadingAudit, setLoadingAudit] = useState(false);
  const [submittingTeachingTask, setSubmittingTeachingTask] = useState(false);
  const [loadingTeachingTask, setLoadingTeachingTask] = useState(false);

  useEffect(() => {
    setLoadingSummary(true);
    api
      .getSummary()
      .then(setSummary)
      .catch((error: Error) => setSummaryError(error.message))
      .finally(() => setLoadingSummary(false));
  }, [api]);

  useEffect(() => {
    const taskId = window.localStorage.getItem(TEACHING_TASK_STORAGE_KEY);
    if (!taskId) {
      return;
    }
    setLoadingTeachingTask(true);
    api
      .getTeachingTask(taskId)
      .then(setTeachingTask)
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setLoadingTeachingTask(false));
  }, [api]);

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim()) {
      setSearchError("请输入检索词。");
      return;
    }
    setSearching(true);
    setSearchError("");
    setAuditError("");
    setAuditDetail(null);
    api
      .search(query.trim(), limit)
      .then((result) => {
        setSearchResult(result);
        setLoadingAudit(true);
        return api
          .getAudit(result.queryId)
          .then(setAuditDetail)
          .catch((error: Error) => setAuditError(error.message))
          .finally(() => setLoadingAudit(false));
      })
      .catch((error: Error) => setSearchError(error.message))
      .finally(() => setSearching(false));
  }

  function handleTeachingTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teachingQuestion.trim() || !learningGoal.trim()) {
      setTeachingError("请输入题目和学习目标。");
      return;
    }
    const clientRequestId = `local-${Date.now()}`;
    setSubmittingTeachingTask(true);
    setTeachingError("");
    api
      .submitTeachingTask({
        clientRequestId,
        questionText: teachingQuestion.trim(),
        learningGoal: learningGoal.trim(),
        evidenceLimit: limit,
      })
      .then((task) => {
        window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
        setTeachingTask(task);
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setSubmittingTeachingTask(false));
  }

  return (
    <main className="app-shell">
      <section className="topbar">
        <div>
          <p className="eyebrow">Math Agent RAG</p>
          <h1>教材证据检索控制台</h1>
        </div>
        <div className="backend-pill">
          <Database size={16} />
          <span>{DEFAULT_BACKEND_URL}</span>
        </div>
      </section>

      <section className="workspace">
        <aside className="side-panel">
          <PanelTitle icon={<BookOpen size={18} />} title="教材资源" />
          {loadingSummary ? (
            <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取教材目录中" />
          ) : summaryError ? (
            <StatusLine icon={<AlertCircle size={16} />} text={summaryError} tone="danger" />
          ) : summary ? (
            <div className="metric-grid">
              <Metric label="教材" value={summary.bookCount} />
              <Metric label="Chunks" value={summary.totalChunkCount} />
              <Metric label="PDF 页" value={summary.totalPageCount} />
            </div>
          ) : null}

          <div className="divider" />

          <PanelTitle icon={<Search size={18} />} title="检索参数" />
          <form className="search-form" onSubmit={handleSearch}>
            <label>
              <span>Query</span>
              <input value={query} onChange={(event) => setQuery(event.target.value)} />
            </label>
            <label>
              <span>Top K</span>
              <input
                type="number"
                min={1}
                max={20}
                value={limit}
                onChange={(event) => setLimit(Number(event.target.value))}
              />
            </label>
            <button type="submit" disabled={searching}>
              {searching ? <Loader2 className="spin" size={17} /> : <Search size={17} />}
              <span>检索</span>
            </button>
          </form>

          <div className="divider" />

          <PanelTitle icon={<BookOpen size={18} />} title="教学任务编排" />
          <form className="search-form" onSubmit={handleTeachingTask}>
            <label>
              <span>想学什么</span>
              <input value={learningGoal} onChange={(event) => setLearningGoal(event.target.value)} />
            </label>
            <label>
              <span>题目/问题</span>
              <input value={teachingQuestion} onChange={(event) => setTeachingQuestion(event.target.value)} />
            </label>
            <button type="submit" disabled={submittingTeachingTask}>
              {submittingTeachingTask ? <Loader2 className="spin" size={17} /> : <BookOpen size={17} />}
              <span>生成讲义任务</span>
            </button>
          </form>
        </aside>

        <section className="result-panel">
          <div className="result-header">
            <div>
              <p className="eyebrow">Evidence</p>
              <h2>命中证据</h2>
            </div>
            {searchResult ? (
              <div className="strategy-pill">
                <ShieldCheck size={16} />
                <span>{searchResult.retrievalStrategy}</span>
              </div>
            ) : null}
          </div>

          {searchError ? <StatusLine icon={<AlertCircle size={16} />} text={searchError} tone="danger" /> : null}

          {!searchResult && !searchError ? (
            <div className="empty-state">输入教材术语、定义、题干片段或公式关键词后开始检索。</div>
          ) : null}

          {searchResult ? (
            <>
              <div className="audit-row">
                <span>审计追踪号</span>
                <strong>{searchResult.queryId}</strong>
              </div>
              {loadingAudit ? (
                <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取审计详情中" />
              ) : auditError ? (
                <StatusLine icon={<AlertCircle size={16} />} text={auditError} tone="danger" />
              ) : auditDetail ? (
                <AuditDetailPanel audit={auditDetail} />
              ) : null}
              <div className="hit-list">
                {searchResult.hits.map((hit, index) => (
                  <EvidenceCard key={hit.chunkId} hit={hit} rank={index + 1} />
                ))}
              </div>
            </>
          ) : null}

          <TeachingTaskPanel
            task={teachingTask}
            loading={loadingTeachingTask}
            error={teachingError}
          />
        </section>
      </section>
    </main>
  );
}

function TeachingTaskPanel({
  task,
  loading,
  error,
}: {
  task: TeachingTaskResponse | null;
  loading: boolean;
  error: string;
}) {
  return (
    <section className="teaching-task">
      <div className="result-header">
        <div>
          <p className="eyebrow">Teaching DAG</p>
          <h2>可恢复教学任务</h2>
        </div>
        {task ? <div className="strategy-pill">{task.status}</div> : null}
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在恢复上次教学任务" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {!task && !loading && !error ? (
        <div className="empty-state compact">提交教学任务后，这里会展示 DAG、ReAct 轨迹、教材证据和 LaTeX 讲义草稿。</div>
      ) : null}
      {task ? (
        <div className="teaching-grid">
          <div className="task-meta">
            <span>Task</span>
            <strong>{task.taskId}</strong>
            <span>Learning goal</span>
            <strong>{task.learningGoal}</strong>
          </div>
          <div className="node-list">
            {task.nodes.map((node) => (
              <div className="node-item" key={node.code}>
                <strong>{node.name}</strong>
                <span>{node.summary}</span>
              </div>
            ))}
          </div>
          <div className="react-list">
            {task.reactTrace.map((step, index) => (
              <div className="react-item" key={`${step.phase}-${index}`}>
                <strong>{step.phase}</strong>
                <span>{step.toolName ? `${step.toolName}: ` : ""}{step.content}</span>
              </div>
            ))}
          </div>
          <div className="hit-list">
            {task.evidence.map((item) => (
              <article className="evidence-card teaching-evidence-card" key={item.chunkId}>
                <div className="scope-badge">{item.sourceScope}</div>
                <div className="card-main">
                  <div className="card-head">
                    <h3>{item.sourceTitle}</h3>
                  </div>
                  <div className="meta-row">
                    <span>{item.chunkId}</span>
                    <span>PDF {item.pageNo}</span>
                  </div>
                  <p className="snippet">{item.snippet}</p>
                </div>
              </article>
            ))}
          </div>
          <pre className="formula-block handout">{task.handoutLatex}</pre>
        </div>
      ) : null}
    </section>
  );
}

function PanelTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="panel-title">
      {icon}
      <span>{title}</span>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value.toLocaleString("zh-CN")}</strong>
    </div>
  );
}

function StatusLine({
  icon,
  text,
  tone = "muted",
}: {
  icon: React.ReactNode;
  text: string;
  tone?: "muted" | "danger";
}) {
  return (
    <div className={`status-line ${tone}`}>
      {icon}
      <span>{text}</span>
    </div>
  );
}

function AuditDetailPanel({ audit }: { audit: RetrievalAuditDetail }) {
  const firstHit = audit.hits[0];
  return (
    <section className="audit-detail">
      <div className="audit-detail-grid">
        <Metric label="耗时 ms" value={audit.elapsedMs} />
        <Metric label="命中" value={audit.hitCount} />
        <Metric label="Top K" value={audit.requestedLimit} />
      </div>
      <div className="audit-detail-row">
        <span>Endpoint</span>
        <strong>{audit.requestContext?.endpoint || "未记录"}</strong>
      </div>
      <div className="audit-detail-row">
        <span>主体</span>
        <strong>
          {audit.subjectType || "anonymous"}
          {audit.subjectId ? ` / ${audit.subjectId}` : ""}
        </strong>
      </div>
      {firstHit ? (
        <div className="audit-detail-row">
          <span>Top hit</span>
          <strong>
            #{firstHit.rankNo} {firstHit.chunkId} / {firstHit.pageQualityLabel}
          </strong>
        </div>
      ) : null}
    </section>
  );
}

function EvidenceCard({ hit, rank }: { hit: TextbookSearchHit; rank: number }) {
  return (
    <article className="evidence-card">
      <div className="card-rank">{rank}</div>
      <div className="card-main">
        <div className="card-head">
          <div>
            <h3>{hit.sectionTitle || hit.bookName}</h3>
            <p>
              {hit.bookName} / {hit.volume}
            </p>
          </div>
          <QualityBadge label={hit.pageQualityLabel} />
        </div>
        <div className="meta-row">
          <span>PDF {hit.pageNo}</span>
          <span>印刷页 {hit.printedPageNo || "未识别"}</span>
          <span>Score {hit.score.toFixed(4)}</span>
          <span>{hit.retrievalStrategy}</span>
        </div>
        <p className="chapter-path">{hit.chapterPath.join(" / ")}</p>
        <p className="snippet">{hit.textSnippet}</p>
        {hit.formulaText ? <pre className="formula-block">{hit.formulaText.slice(0, 360)}</pre> : null}
        <div className="source-row">
          <span>{hit.chunkId}</span>
          <span>{hit.sourcePageImage}</span>
        </div>
      </div>
    </article>
  );
}

function QualityBadge({ label }: { label: string }) {
  const tone = label === "content_page" ? "good" : "warn";
  return <span className={`quality-badge ${tone}`}>{label}</span>;
}
