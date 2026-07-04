import { RetrievalAuditDetail, TextbookSearchHit } from "../../shared/api/textbookApi";
import { Metric } from "./panelShared";

export function AuditDetailPanel({ audit }: { audit: RetrievalAuditDetail }) {
  const firstHit = audit.hits[0];
  return (
    <section className="audit-detail">
      <div className="audit-detail-grid">
        <Metric label="Elapsed ms" value={audit.elapsedMs} />
        <Metric label="Hits" value={audit.hitCount} />
        <Metric label="Top K" value={audit.requestedLimit} />
      </div>
      <div className="audit-detail-row">
        <span>Endpoint</span>
        <strong>{audit.requestContext?.endpoint || "not recorded"}</strong>
      </div>
      <div className="audit-detail-row">
        <span>Subject</span>
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
          <span>Printed {hit.printedPageNo || "unknown"}</span>
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
