import { FormEvent } from "react";
import katex from "katex";
import {
  AlertCircle,
  BookOpen,
  Check,
  Clock,
  Database,
  Download,
  Eye,
  FileText,
  Loader2,
  ShieldCheck,
} from "lucide-react";
import { TeachingTaskResponse } from "../../shared/api/textbookApi";
import { formatSimilarity, stageLabel, StatusLine } from "./panelShared";

export function TeachingTaskPanel({
  task,
  loading,
  error,
  history,
  loadingHistory,
  version,
  previewLatex,
  previewPdfUrl,
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
  onPreviewPdf,
  onExportLatex,
  onExportPdf,
  onExportBatchZip,
  onSelectHistory,
  onFeedbackRatingChange,
  onFeedbackDecisionChange,
  onFeedbackCommentChange,
  onSubmitFeedback,
}: {
  task: TeachingTaskResponse | null;
  loading: boolean;
  error: string;
  history: TeachingTaskResponse[];
  loadingHistory: boolean;
  version: "teacher" | "student";
  previewLatex: string;
  previewPdfUrl: string;
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
  onPreviewPdf: () => void;
  onExportLatex: () => void;
  onExportPdf: () => void;
  onExportBatchZip: () => void;
  onSelectHistory: (task: TeachingTaskResponse) => void;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const busy = Boolean(action);
  const normalizedStatus = task?.status?.toUpperCase() ?? "";
  const selectedDraft = version === "student"
    ? task?.studentHandoutLatex ?? task?.handoutLatex ?? ""
    : task?.teacherHandoutLatex ?? task?.handoutLatex ?? "";
  const completed = normalizedStatus === "COMPLETED" || normalizedStatus === "SUCCESS";

  return (
    <section className="teaching-task">
      <div className="result-header">
        <div>
          <p className="eyebrow">讲义中心</p>
          <h2>生成结果、预览与下载</h2>
        </div>
        {task ? <div className="strategy-pill">{statusLabel(task.status)}</div> : null}
      </div>

      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在恢复上次教学任务" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {completed ? <StatusLine icon={<Check size={16} />} text="讲义已生成。可以直接预览 PDF、下载 PDF，或打开 TeX 做人工审查。" /> : null}

      <HistoryPanel
        history={history}
        currentTaskId={task?.taskId}
        loading={loadingHistory}
        onSelectHistory={onSelectHistory}
      />

      {!task && !loading && !error ? (
        <div className="empty-state compact">提交讲义主题后，这里会显示 PDF 预览、下载入口、历史记录和人工反馈。</div>
      ) : null}

      {task ? (
        <div className="teaching-grid">
          <section className="handout-primary-panel">
            <div className="handout-primary-head">
              <div>
                <p className="eyebrow">当前讲义</p>
                <h3>{task.learningGoal || task.questionText || "未命名讲义"}</h3>
                <span>{task.taskId}</span>
              </div>
              <label>
                <span>版本</span>
                <select className="form-select" value={version} onChange={(event) => onVersionChange(event.target.value as "teacher" | "student")}>
                  <option value="teacher">教师版</option>
                  <option value="student">学生版</option>
                </select>
              </label>
            </div>

            <div className="handout-toolbar primary">
              <button type="button" className="btn btn-primary" onClick={onExportPdf} disabled={busy || !selectedDraft}>
                {action === "pdf" ? <Loader2 className="spin" size={16} /> : <Download size={16} />}
                <span>下载 PDF</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onPreviewPdf} disabled={busy || !selectedDraft}>
                {action === "preview-pdf" ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
                <span>预览 PDF</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onPreviewLatex} disabled={busy || !selectedDraft}>
                {action === "preview" ? <Loader2 className="spin" size={16} /> : <BookOpen size={16} />}
                <span>审查 TeX</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onExportLatex} disabled={busy || !selectedDraft}>
                {action === "latex" ? <Loader2 className="spin" size={16} /> : <FileText size={16} />}
                <span>下载 TeX</span>
              </button>
            </div>

            <div className="batch-export-row">
              <label>
                <span>ZIP 内文件夹</span>
                <input
                  value={batchFolderPath}
                  onChange={(event) => onBatchFolderPathChange(event.target.value)}
                  placeholder={`handouts/${task.taskId}`}
                />
              </label>
              <button type="button" className="btn btn-secondary" onClick={onExportBatchZip} disabled={busy || !selectedDraft}>
                {action === "zip" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
                <span>打包下载</span>
              </button>
            </div>

            {exportMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={exportMessage} /> : null}

            {previewPdfUrl ? (
              <div className="pdf-preview-frame">
                <iframe src={previewPdfUrl} title="PDF 讲义预览" />
              </div>
            ) : (
              <div className="handout-preview-placeholder">
                <FileText size={22} />
                <strong>{selectedDraft ? "讲义文件已生成" : "当前版本暂无讲义内容"}</strong>
                <span>{selectedDraft ? "点击“预览 PDF”查看真实渲染效果，或直接下载给老师/管理员审查。" : "请重新生成或切换版本。"}</span>
                {selectedDraft ? <small>{selectedDraft.length.toLocaleString("zh-CN")} 字符 TeX 源码</small> : null}
              </div>
            )}

            {previewLatex ? (
              <details className="latex-review-panel" open>
                <summary>TeX 源码审查</summary>
                <pre className="formula-block handout preview">{previewLatex}</pre>
              </details>
            ) : null}
          </section>

          <section className="teaching-summary-grid">
            <InfoTile label="模板" value={task.selectedTemplate?.displayName ?? "标准讲义"} />
            <InfoTile label="生成方式" value={task.aiDraft?.enabled ? "模型辅助生成" : "模板与检索生成"} />
            <InfoTile label="当前状态" value={statusLabel(task.status)} />
            <InfoTile label="记忆复用" value={task.memoryReuse?.reused ? "已复用" : "未复用"} />
          </section>

          {task.aiDraft ? (
            <details className="ai-draft-panel">
              <summary>
                <div>
                  <p className="eyebrow">生成诊断</p>
                  <h3>{task.aiDraft.structured ? "内容结构已解析" : "内容需要人工复核"}</h3>
                </div>
                <StatusBadgeText text={`${task.aiDraft.retryCount}/${task.aiDraft.maxRetries} 次重试`} />
              </summary>
              <div className="diagnostic-meta">
                <span>模型：{task.aiDraft.enabled ? `${providerLabel(task.aiDraft.providerName)} / ${task.aiDraft.modelCode}` : "未启用"}</span>
                <span>Token：{task.aiDraft.totalTokens ?? 0}</span>
              </div>
              {task.aiDraft.structured ? (
                <div className="ai-draft-content">
                  <div className="summary-card">
                    <span>教师讲解要点</span>
                    <strong><InlineMathText text={shortText(task.aiDraft.teacherExplanation, 120)} /></strong>
                    <details>
                      <summary>查看后台生成文本</summary>
                      <p><InlineMathText text={task.aiDraft.teacherExplanation} /></p>
                    </details>
                  </div>
                  <div className="summary-card">
                    <span>学生提示</span>
                    <strong><InlineMathText text={shortText(task.aiDraft.studentHint, 100)} /></strong>
                  </div>
                  {task.aiDraft.knowledgePoints.length ? (
                    <div className="tag-list">{task.aiDraft.knowledgePoints.map((item) => <span key={item}><InlineMathText text={item} /></span>)}</div>
                  ) : null}
                </div>
              ) : (
                <div className="summary-card">
                  <span>返回摘要</span>
                  <strong><InlineMathText text={shortText(task.aiDraft.parseError || task.aiDraft.content || task.aiDraft.message, 140)} /></strong>
                </div>
              )}
            </details>
          ) : null}

          <details className="review-details">
            <summary>流程与证据</summary>
            <div className="node-list">
              {task.nodes.map((node) => (
                <div className="node-item" key={node.code}>
                  <strong>{node.name}</strong>
                  <span>{shortText(node.summary, 96)}</span>
                </div>
              ))}
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
            {task.evidence.length ? (
              <div className="hit-list">
                {task.evidence.slice(0, 5).map((item) => (
                  <article className="evidence-card teaching-evidence-card" key={item.chunkId}>
                    <div className="scope-badge">{scopeLabel(item.sourceScope)}</div>
                    <div className="card-main">
                      <div className="card-head">
                        <h3>{item.sourceTitle}</h3>
                      </div>
                      <div className="meta-row">
                        <span>{shortText(item.chunkId, 28)}</span>
                        <span>{item.sourceScope === "QUESTION_BANK" || item.pageNo <= 0 ? "题库题目" : `PDF ${item.pageNo}`}</span>
                      </div>
                      <p className="snippet">{shortText(cleanSnippet(item.snippet), 96)}</p>
                    </div>
                  </article>
                ))}
              </div>
            ) : null}
          </details>

          <form className="human-feedback-panel" onSubmit={onSubmitFeedback}>
            <div className="feedback-head">
              <strong>人工反馈</strong>
              {feedbackMessage ? <span>{feedbackMessage}</span> : null}
            </div>
            <div className="feedback-quality-list" aria-label="讲义质量审查项">
              {[
                "页眉页脚完整",
                "教师/学生版区分清楚",
                "公式渲染正确",
                "PDF 排版无重叠",
                "教材/题型来源可追溯",
              ].map((item) => (
                <span key={item}><Check size={13} />{item}</span>
              ))}
            </div>
            <div className="feedback-grid">
              <label>
                <span>评分</span>
                <input
                  className="form-input"
                  type="number"
                  min={1}
                  max={5}
                  value={feedbackRating}
                  onChange={(event) => onFeedbackRatingChange(Number(event.target.value))}
                />
              </label>
              <label>
                <span>处理结论</span>
                <select className="form-select" value={feedbackDecision} onChange={(event) => onFeedbackDecisionChange(event.target.value)}>
                  <option value="helpful">可用</option>
                  <option value="confusing">不清楚</option>
                  <option value="needs_revision">需要修改</option>
                </select>
              </label>
            </div>
            <label>
              <span>修改意见</span>
              <textarea
                className="form-textarea"
                value={feedbackComment}
                onChange={(event) => onFeedbackCommentChange(event.target.value)}
                placeholder="记录要保留、重写或补充的地方。"
              />
            </label>
            <button type="submit" className="btn btn-secondary" disabled={submittingFeedback}>
              {submittingFeedback ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>提交反馈</span>
            </button>
          </form>

          {task.memoryReuse ? (
            <details className="review-details">
              <summary>记忆复用说明</summary>
              <p>{humanMemoryReason(task.memoryReuse.reason)} 相似度：{formatSimilarity(task.memoryReuse.similarity)}</p>
            </details>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function HistoryPanel({
  history,
  currentTaskId,
  loading,
  onSelectHistory,
}: {
  history: TeachingTaskResponse[];
  currentTaskId?: string;
  loading: boolean;
  onSelectHistory: (task: TeachingTaskResponse) => void;
}) {
  return (
    <div className="teaching-history-panel">
      <div className="teaching-history-head">
        <strong>历史讲义任务</strong>
        {loading ? <Loader2 className="spin" size={14} /> : <Clock size={14} />}
      </div>
      {!history.length ? (
        <div className="empty-state compact">当前账号暂无历史讲义任务。</div>
      ) : (
        <div className="teaching-history-list">
          {history.map((item) => {
            const hasHandout = Boolean(item.teacherHandoutLatex || item.studentHandoutLatex || item.handoutLatex);
            return (
              <button
                type="button"
                className={currentTaskId === item.taskId ? "teaching-history-item active" : "teaching-history-item"}
                key={item.taskId}
                onClick={() => onSelectHistory(item)}
              >
                <strong>{item.learningGoal || item.questionText || "未命名讲义"}</strong>
                <span>{statusLabel(item.status)} · {shortText(item.taskId, 22)}</span>
                <span className={hasHandout ? "teaching-history-action" : "teaching-history-action muted"}>
                  {hasHandout ? "打开并预览 PDF，可下载/审查" : "暂无可预览讲义"}
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function InfoTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="info-tile">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function StatusBadgeText({ text }: { text: string }) {
  return <div className="strategy-pill">{text}</div>;
}

function InlineMathText({ text }: { text: string }) {
  return (
    <>
      {splitMathText(text).map((segment) => {
        if (!segment.math) {
          return <span key={segment.key}>{segment.text}</span>;
        }
        const html = katex.renderToString(normalizeLatexExpression(segment.text), {
          displayMode: false,
          throwOnError: false,
          strict: false,
          trust: false,
        });
        return <span className="math-render inline" dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
      })}
    </>
  );
}

function splitMathText(text: string) {
  const segments: Array<{ key: string; text: string; math: boolean }> = [];
  let index = 0;
  let key = 0;
  while (index < text.length) {
    const start = text.indexOf("$", index);
    if (start < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(index), math: false });
      break;
    }
    if (start > index) {
      segments.push({ key: `text-${key++}`, text: text.slice(index, start), math: false });
    }
    const end = text.indexOf("$", start + 1);
    if (end < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(start), math: false });
      break;
    }
    const expression = text.slice(start + 1, end).trim();
    if (expression) {
      segments.push({ key: `math-${key++}`, text: expression, math: true });
    }
    index = end + 1;
  }
  return segments.length ? segments : [{ key: "text-0", text, math: false }];
}

function normalizeLatexExpression(value: string) {
  return value.replace(/\\\\(?=[A-Za-z])/g, "\\");
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    COMPLETED: "已完成",
    RUNNING: "生成中",
    FAILED: "失败",
    PENDING: "等待中",
    CREATED: "已创建",
  };
  return labels[status] ?? status;
}

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    openai: "OpenAI",
    dashscope: "通义千问",
    deepseek: "DeepSeek",
    ark: "火山方舟",
  };
  return labels[provider] ?? provider;
}

function shortText(value: string | undefined, maxLength: number) {
  const text = (value ?? "").replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) {
    return text || "暂无内容";
  }
  return `${text.slice(0, Math.max(0, maxLength - 1))}…`;
}

function humanMemoryReason(value: string | undefined) {
  const text = (value ?? "").trim();
  if (!text) {
    return "本次没有记录可复用的历史记忆。";
  }
  if (text.toLowerCase().includes("no reusable memory")) {
    return "本次没有命中可复用的历史学习记录。";
  }
  return shortText(text, 120);
}

function scopeLabel(scope: string) {
  const labels: Record<string, string> = {
    PUBLIC_TEXTBOOK: "公开教材",
    QUESTION_BANK: "题库",
    TEACHER_PRIVATE: "教师资料",
    MATH_VIP: "教研共享",
  };
  return labels[scope] ?? scope;
}

function cleanSnippet(value: string | undefined) {
  return (value ?? "")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/[#*_`>$]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
