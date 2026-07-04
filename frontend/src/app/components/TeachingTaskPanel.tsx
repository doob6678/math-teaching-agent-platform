import { FormEvent } from "react";
import { AlertCircle, BookOpen, Database, Loader2, ShieldCheck } from "lucide-react";
import { TeachingTaskResponse } from "../../shared/api/textbookApi";
import { formatSimilarity, stageLabel, StatusLine } from "./panelShared";

export function TeachingTaskPanel({
  task,
  loading,
  error,
  version,
  previewLatex,
  action,
  exportMessage,
  feedbackRating,
  feedbackDecision,
  feedbackComment,
  submittingFeedback,
  feedbackMessage,
  batchFolderPath,
  onVersionChange,
  onBatchFolderPathChange,
  onPreviewLatex,
  onExportLatex,
  onExportPdf,
  onExportBatchZip,
  onFeedbackRatingChange,
  onFeedbackDecisionChange,
  onFeedbackCommentChange,
  onSubmitFeedback,
}: {
  task: TeachingTaskResponse | null;
  loading: boolean;
  error: string;
  version: "teacher" | "student";
  previewLatex: string;
  action: string;
  exportMessage: string;
  feedbackRating: number;
  feedbackDecision: string;
  feedbackComment: string;
  submittingFeedback: boolean;
  feedbackMessage: string;
  batchFolderPath: string;
  onVersionChange: (value: "teacher" | "student") => void;
  onBatchFolderPathChange: (value: string) => void;
  onPreviewLatex: () => void;
  onExportLatex: () => void;
  onExportPdf: () => void;
  onExportBatchZip: () => void;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const busy = Boolean(action);
  const selectedDraft = version === "student"
    ? task?.studentHandoutLatex ?? task?.handoutLatex ?? ""
    : task?.teacherHandoutLatex ?? task?.handoutLatex ?? "";
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
          <div className="memory-strip">
            <div>
              <span>Memory</span>
              <strong>{task.memoryReuse?.reused ? "reused" : "not reused"}</strong>
            </div>
            <div>
              <span>Scope</span>
              <strong>{task.memoryReuse?.reuseScope ?? "none"}</strong>
            </div>
            <div>
              <span>Similarity</span>
              <strong>{formatSimilarity(task.memoryReuse?.similarity)}</strong>
            </div>
            <p>{task.memoryReuse?.reason ?? "未记录记忆复用决策。"}</p>
          </div>
          {task.stageTimings?.length ? (
            <div className="timing-list">
              {task.stageTimings.map((timing) => (
                <div className="timing-item" key={timing.stage}>
                  <span>{stageLabel(timing.stage)}</span>
                  <strong>{timing.elapsedMs} ms</strong>
                </div>
              ))}
            </div>
          ) : null}
          {task.aiDraft ? (
            <div className="ai-draft-panel">
              <div>
                <span>AI model</span>
                <strong>
                  {task.aiDraft.enabled ? `${task.aiDraft.providerName}/${task.aiDraft.modelCode}` : "not enabled"}
                </strong>
              </div>
              <div>
                <span>Tokens</span>
                <strong>{task.aiDraft.totalTokens}</strong>
              </div>
              <div>
                <span>Parse</span>
                <strong>{task.aiDraft.structured ? "structured" : "raw"}</strong>
              </div>
              <div>
                <span>Retry</span>
                <strong>
                  {task.aiDraft.retryCount}/{task.aiDraft.maxRetries}
                  {task.aiDraft.recoveredAfterRetry ? " recovered" : ""}
                </strong>
              </div>
              {task.aiDraft.structured ? (
                <div className="ai-draft-content">
                  <p>{task.aiDraft.teacherExplanation}</p>
                  <p>{task.aiDraft.studentHint}</p>
                  {task.aiDraft.knowledgePoints.length ? (
                    <ul>
                      {task.aiDraft.knowledgePoints.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  ) : null}
                  {task.aiDraft.followUpQuestions.length ? (
                    <ul>
                      {task.aiDraft.followUpQuestions.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              ) : (
                <p>{task.aiDraft.parseError || task.aiDraft.content || task.aiDraft.message}</p>
              )}
              {task.aiDraft.recoveryEvents?.length ? (
                <div className="ai-recovery-list">
                  {task.aiDraft.recoveryEvents.map((event, index) => (
                    <div
                      className={event.structured ? "ai-recovery-event good" : "ai-recovery-event"}
                      key={`${event.eventType}:${event.providerName}:${event.attemptNo}:${index}`}
                    >
                      <strong>{event.eventType}</strong>
                      <span>
                        {event.providerName}/{event.modelCode} attempt {event.attemptNo}
                      </span>
                      <p>{event.message}</p>
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}
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
          <div className="handout-version-row">
            <label>
              <span>Handout version</span>
              <select value={version} onChange={(event) => onVersionChange(event.target.value as "teacher" | "student")}>
                <option value="teacher">Teacher</option>
                <option value="student">Student</option>
              </select>
            </label>
          </div>
          <div className="handout-toolbar">
            <button type="button" onClick={onPreviewLatex} disabled={busy}>
              {action === "preview" ? <Loader2 className="spin" size={16} /> : <BookOpen size={16} />}
              <span>Preview LaTeX</span>
            </button>
            <button type="button" onClick={onExportLatex} disabled={busy}>
              {action === "latex" ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>Export TeX</span>
            </button>
            <button type="button" onClick={onExportPdf} disabled={busy}>
              {action === "pdf" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>Export PDF</span>
            </button>
          </div>
          <div className="batch-export-row">
            <label>
              <span>ZIP folder</span>
              <input
                value={batchFolderPath}
                onChange={(event) => onBatchFolderPathChange(event.target.value)}
                placeholder={`handouts/${task.taskId}`}
              />
            </label>
            <button type="button" onClick={onExportBatchZip} disabled={busy}>
              {action === "zip" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>Export ZIP</span>
            </button>
          </div>
          {exportMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={exportMessage} /> : null}
          {previewLatex ? (
            <pre className="formula-block handout preview">{previewLatex}</pre>
          ) : (
            <pre className="formula-block handout">{selectedDraft}</pre>
          )}
          <form className="human-feedback-panel" onSubmit={onSubmitFeedback}>
            <div className="feedback-head">
              <strong>Human feedback</strong>
              {feedbackMessage ? <span>{feedbackMessage}</span> : null}
            </div>
            <div className="feedback-grid">
              <label>
                <span>Rating</span>
                <input
                  type="number"
                  min={1}
                  max={5}
                  value={feedbackRating}
                  onChange={(event) => onFeedbackRatingChange(Number(event.target.value))}
                />
              </label>
              <label>
                <span>Decision</span>
                <select value={feedbackDecision} onChange={(event) => onFeedbackDecisionChange(event.target.value)}>
                  <option value="helpful">Helpful</option>
                  <option value="confusing">Confusing</option>
                  <option value="needs_revision">Needs revision</option>
                </select>
              </label>
            </div>
            <label>
              <span>Comment</span>
              <textarea
                value={feedbackComment}
                onChange={(event) => onFeedbackCommentChange(event.target.value)}
                placeholder="Record what should be improved or kept."
              />
            </label>
            <button type="submit" disabled={submittingFeedback}>
              {submittingFeedback ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>Submit feedback</span>
            </button>
          </form>
        </div>
      ) : null}
    </section>
  );
}
