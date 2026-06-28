import { AlertCircle, BookOpen, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  TextbookSearchHit,
  TextbookSearchResponse,
  TextbookSummary,
  createTextbookApiClient,
} from "../shared/api/textbookApi";

const DEFAULT_BACKEND_URL = import.meta.env.VITE_BACKEND_URL ?? "http://127.0.0.1:8080";

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
  const [searchError, setSearchError] = useState("");
  const [loadingSummary, setLoadingSummary] = useState(false);
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    setLoadingSummary(true);
    api
      .getSummary()
      .then(setSummary)
      .catch((error: Error) => setSummaryError(error.message))
      .finally(() => setLoadingSummary(false));
  }, [api]);

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim()) {
      setSearchError("请输入检索词。");
      return;
    }
    setSearching(true);
    setSearchError("");
    api
      .search(query.trim(), limit)
      .then(setSearchResult)
      .catch((error: Error) => setSearchError(error.message))
      .finally(() => setSearching(false));
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
            <div className="hit-list">
              {searchResult.hits.map((hit, index) => (
                <EvidenceCard key={hit.chunkId} hit={hit} rank={index + 1} />
              ))}
            </div>
          ) : null}
        </section>
      </section>
    </main>
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
