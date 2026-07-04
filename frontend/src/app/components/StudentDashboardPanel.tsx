import { AlertCircle, Database, Loader2 } from "lucide-react";
import { StudentDashboardResponse } from "../../shared/api/textbookApi";
import { boundedPercent, StatusLine } from "./panelShared";

export function StudentDashboardPanel({
  dashboard,
  loading,
  error,
  onRefresh,
}: {
  dashboard: StudentDashboardResponse | null;
  loading: boolean;
  error: string;
  onRefresh?: () => void;
}) {
  const latestScore = dashboard?.scoreTrend.at(-1);
  return (
    <section className="student-dashboard">
      <div className="result-header">
        <div>
          <p className="eyebrow">Student Profile</p>
          <h2>学习画像</h2>
        </div>
        <div className="result-actions">
          {dashboard ? <div className="strategy-pill">{dashboard.viewerRole}</div> : null}
          {onRefresh ? (
            <button type="button" className="inline-action" onClick={onRefresh} disabled={loading}>
              {loading ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>刷新快照</span>
            </button>
          ) : null}
        </div>
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取学习画像中" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {dashboard ? (
        <div className="dashboard-grid">
          <div className="profile-strip">
            <div>
              <span>学生</span>
              <strong>{dashboard.studentId}</strong>
            </div>
            <div>
              <span>查看者</span>
              <strong>{dashboard.viewerSubjectId}</strong>
            </div>
            <div>
              <span>最近成绩</span>
              <strong>{latestScore ? `${latestScore.score} / ${latestScore.rankInGrade}名` : "未记录"}</strong>
            </div>
          </div>

          <div className="knowledge-panel">
            <h3>知识点进度</h3>
            <div className="progress-list">
              {dashboard.knowledgeProgress.map((item) => (
                <div className="progress-item" key={item.knowledgePointId ?? item.knowledgePointName}>
                  <div className="progress-head">
                    <strong>{item.knowledgePointName}</strong>
                    <span>{item.progressPercent}%</span>
                  </div>
                  <div className="progress-track" aria-label={`${item.knowledgePointName} ${item.progressPercent}%`}>
                    <div className="progress-fill" style={{ width: `${boundedPercent(item.progressPercent)}%` }} />
                  </div>
                  <p>{item.textbookAnchor}</p>
                </div>
              ))}
            </div>
          </div>

          {dashboard.knowledgeGraph ? (
            <div className="knowledge-graph-panel">
              <div className="knowledge-graph-head">
                <h3>Knowledge Graph</h3>
                <span>{dashboard.knowledgeGraph.generatedFrom}</span>
              </div>
              <div className="knowledge-graph-nodes">
                {dashboard.knowledgeGraph.nodes.map((node) => (
                  <article className={`knowledge-node risk-${node.riskLevel}`} key={node.knowledgePointId}>
                    <div className="knowledge-node-main">
                      <strong>{node.knowledgePointName}</strong>
                      <span>{node.masteryPercent}%</span>
                    </div>
                    <p>{node.chapterPath}</p>
                    <div className="knowledge-node-meta">
                      <span>{node.riskLevel}</span>
                      {node.evidenceLinks.map((link) => (
                        <a href={link.url} key={`${node.knowledgePointId}:${link.sourceType}`}>
                          {link.sourceType}
                        </a>
                      ))}
                    </div>
                  </article>
                ))}
              </div>
              <div className="knowledge-graph-edges">
                {dashboard.knowledgeGraph.edges.map((edge) => (
                  <div className="knowledge-edge" key={edge.edgeId}>
                    <strong>{edge.relationType}</strong>
                    <span>
                      {edge.sourceKnowledgePointId} -&gt; {edge.targetKnowledgePointId}
                    </span>
                    <p>{edge.evidenceSummary}</p>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          <div className="dashboard-column">
            <h3>薄弱点</h3>
            {dashboard.weakPoints.map((item) => (
              <div className="weak-item" key={item.knowledgePointId ?? item.knowledgePointName}>
                <div>
                  <strong>{item.knowledgePointName}</strong>
                  <span>等级 {item.weaknessLevel}</span>
                </div>
                <p>{item.evidenceSummary}</p>
              </div>
            ))}
          </div>

          <div className="dashboard-column">
            <h3>历史问题</h3>
            {dashboard.recentQuestions.map((item) => (
              <div className="question-item" key={item.recordId}>
                <strong>{item.questionTitle}</strong>
                <span>{item.sourceType} / {item.status}</span>
              </div>
            ))}
          </div>

          <div className="dashboard-column score-column">
            <h3>成绩趋势</h3>
            {dashboard.scoreTrend.map((item) => (
              <div className="score-item" key={item.examName}>
                <span>{item.examName}</span>
                <div className="score-bar">
                  <div style={{ width: `${boundedPercent((item.score / 150) * 100)}%` }} />
                </div>
                <strong>{item.score}</strong>
              </div>
            ))}
          </div>

          <div className="scope-list">
            {dashboard.resourceScopes.map((scope) => (
              <span key={scope.scopeCode}>{scope.scopeName ?? scope.scopeCode}</span>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}
