import { RetrievalAuditDetail, TextbookSearchHit } from "../../shared/api/textbookApi";
import { compactText, Metric } from "./panelShared";

export function AuditDetailPanel({ audit }: { audit: RetrievalAuditDetail }) {
  const firstHit = audit.hits[0];
  return (
    <section className="audit-detail">
      <div className="audit-detail-grid">
        <Metric label="耗时 ms" value={audit.elapsedMs} />
        <Metric label="命中数" value={audit.hitCount} />
        <Metric label="返回条数" value={audit.requestedLimit} />
      </div>
      <div className="audit-detail-row">
        <span>来源</span>
        <strong>{audit.requestContext?.endpoint || "未记录"}</strong>
      </div>
      <div className="audit-detail-row">
        <span>说明</span>
        <strong>后端已完成真实检索与权限审计</strong>
      </div>
      {firstHit ? (
        <div className="audit-detail-row">
          <span>首条命中</span>
          <strong>
            #{firstHit.rankNo} {firstHit.chunkId} / {qualityLabel(firstHit.pageQualityLabel)}
          </strong>
        </div>
      ) : null}
    </section>
  );
}

export function EvidenceCard({ hit, rank }: { hit: TextbookSearchHit; rank: number }) {
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
          <span>相关度 {hit.score.toFixed(4)}</span>
          <span>{retrievalStrategyLabel(hit.retrievalStrategy)}</span>
        </div>
        <p className="chapter-path">{hit.chapterPath.join(" / ")}</p>
        <p className="snippet">{compactText(hit.textSnippet, 150)}</p>
        {hit.formulaText ? (
          <details className="review-details">
            <summary>查看公式片段</summary>
            <pre className="formula-block">{hit.formulaText.slice(0, 360)}</pre>
          </details>
        ) : null}
        <div className="source-row">
          <span>{compactText(hit.chunkId, 28)}</span>
          <span>{compactText(hit.sourcePageImage, 32)}</span>
        </div>
      </div>
    </article>
  );
}

function QualityBadge({ label }: { label: string }) {
  const tone = label === "content_page" ? "good" : "warn";
  return <span className={`quality-badge ${tone}`}>{qualityLabel(label)}</span>;
}

function qualityLabel(label: string) {
  const labels: Record<string, string> = {
    content_page: "正文页",
    cover_page: "封面",
    catalog_page: "目录页",
    appendix_page: "附录",
    exercise_page: "习题页",
    unknown: "未识别",
  };
  return labels[label] ?? (label || "未识别");
}

function retrievalStrategyLabel(strategy: string) {
  const labels: Record<string, string> = {
    local_bm25: "本地关键词",
    local_bm25_first: "本地关键词优先",
    milvus_vector: "向量检索",
    hybrid: "混合检索",
  };
  return labels[strategy] ?? strategy;
}
