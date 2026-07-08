import { FormEvent, useEffect, useMemo, useState } from "react";
import { Download, Eye, FileText, Loader2, ShieldCheck } from "lucide-react";
import { TeachingHandoutPdfResponse, TeachingHumanFeedbackResponse, TeachingTaskResponse } from "../../shared/api/textbookApi";
import { PdfCanvasPreview } from "./PdfCanvasPreview";
import { HandoutStructuredPreview, HandoutVersion, LectureHandoutPreview } from "./TeachingTaskPanel";

type PreviewMode = "summary" | "pdf" | "review";

export function HandoutWorkspacePreviewPanel({
  task,
  version,
  previewLatex,
  previewTaskKey,
  previewPdfUrl,
  previewPdfBytes,
  previewPdfMeta,
  previewPdfTaskKey,
  action,
  exportMessage,
  feedbackRating,
  feedbackDecision,
  feedbackComment,
  submittingFeedback,
  feedbackMessage,
  feedbackHistory,
  loadingFeedbackHistory,
  onVersionChange,
  onPreviewPdf,
  onPreviewLatex,
  onExportPdf,
  onFeedbackRatingChange,
  onFeedbackDecisionChange,
  onFeedbackCommentChange,
  onSubmitFeedback,
}: {
  task: TeachingTaskResponse | null;
  version: HandoutVersion;
  previewLatex: string;
  previewTaskKey: string;
  previewPdfUrl: string;
  previewPdfBytes: Uint8Array | null;
  previewPdfMeta: TeachingHandoutPdfResponse | null;
  previewPdfTaskKey: string;
  action: string;
  exportMessage: string;
  feedbackRating: number;
  feedbackDecision: string;
  feedbackComment: string;
  submittingFeedback: boolean;
  feedbackMessage: string;
  feedbackHistory: TeachingHumanFeedbackResponse[];
  loadingFeedbackHistory: boolean;
  onVersionChange: (value: HandoutVersion) => void;
  onPreviewPdf: () => void;
  onPreviewLatex: () => void;
  onExportPdf: () => void;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const [mode, setMode] = useState<PreviewMode>("summary");
  const selectedDraft = task ? handoutDraftForVersion(task, version) : "";
  const taskKey = task ? `${task.taskId}:${version}` : "";
  const pdfPreviewReady = Boolean(task && previewPdfBytes && previewPdfUrl && previewPdfMeta && previewPdfTaskKey === taskKey);
  const latexPreviewReady = Boolean(task && previewLatex.trim() && previewTaskKey === taskKey);
  const taskCompleted = task?.status === "COMPLETED";
  const modelLine = task?.aiDraft?.enabled ? `${providerLabel(task.aiDraft.providerName)} / ${task.aiDraft.modelCode}` : "模板生成";
  const statusText = useMemo(() => statusLabel(task?.status), [task?.status]);

  useEffect(() => {
    setMode("summary");
  }, [task?.taskId]);

  useEffect(() => {
    if (taskCompleted && pdfPreviewReady) {
      setMode((current) => current === "summary" ? "pdf" : current);
    }
  }, [taskCompleted, pdfPreviewReady]);

  function openPdfMode() {
    setMode("pdf");
    if (!pdfPreviewReady) onPreviewPdf();
  }

  function openReviewMode() {
    setMode("review");
    if (!latexPreviewReady) onPreviewLatex();
  }

  return (
    <section className="handout-workspace-preview" id="handout-review-panel">
      <div className="handout-workspace-topbar">
        <div>
          <span>讲义预览</span>
          <strong>{task ? task.learningGoal || task.questionText || "当前讲义" : "等待讲义任务"}</strong>
        </div>
        <div className="handout-workspace-version-switch" role="tablist" aria-label="讲义版本">
          <button type="button" className={`handout-preview-mode${version === "teacher" ? " active" : ""}`} onClick={() => onVersionChange("teacher")}>
            教师版
          </button>
          <button type="button" className={`handout-preview-mode${version === "student" ? " active" : ""}`} onClick={() => onVersionChange("student")}>
            学生版
          </button>
          {task ? (
            <button type="button" className={`handout-preview-mode${version === "lecture" ? " active" : ""}`} onClick={() => onVersionChange("lecture")}>
              横版讲解
            </button>
          ) : null}
        </div>
      </div>

      {task ? (
        <>
          <div className="handout-workspace-metrics">
            <div><span>任务状态</span><strong>{statusText}</strong></div>
            <div><span>当前模型</span><strong>{modelLine}</strong></div>
            <div><span>命中来源</span><strong>{task.evidence.length} 条</strong></div>
            <div><span>Token</span><strong>{task.aiDraft?.totalTokens ?? 0}</strong></div>
          </div>

          <div className="handout-workspace-actions">
            <div className="handout-preview-mode-switch" role="tablist" aria-label="讲义预览模式">
              <button type="button" className={`handout-preview-mode${mode === "summary" ? " active" : ""}`} onClick={() => setMode("summary")}>摘要</button>
              <button type="button" className={`handout-preview-mode${mode === "pdf" ? " active" : ""}`} onClick={openPdfMode}>PDF</button>
              <button type="button" className={`handout-preview-mode${mode === "review" ? " active" : ""}`} onClick={openReviewMode}>审查</button>
            </div>
            <div className="handout-workspace-button-row">
              <button className="btn btn-secondary btn-sm" type="button" onClick={openPdfMode}>
                {action === "preview-pdf" ? <Loader2 className="spin" size={15} /> : <Eye size={15} />}
                <span>打开 PDF</span>
              </button>
              <button className="btn btn-secondary btn-sm" type="button" onClick={openReviewMode}>
                {action === "preview" ? <Loader2 className="spin" size={15} /> : <FileText size={15} />}
                <span>审查结构</span>
              </button>
              <button className="btn btn-primary btn-sm" type="button" onClick={onExportPdf} disabled={!taskCompleted || !selectedDraft}>
                {action === "pdf" ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
                <span>下载 PDF</span>
              </button>
            </div>
          </div>

          {action ? <div className="handout-workspace-status" role="status" aria-live="polite">{actionText(action)}</div> : null}
          {exportMessage ? <div className="handout-workspace-status">{exportMessage}</div> : null}

          <div className="handout-workspace-body">
            {mode === "summary" ? <LectureHandoutPreview task={task} version={version} /> : null}

            {mode === "pdf" ? (
              pdfPreviewReady && previewPdfBytes && previewPdfMeta ? (
                <PdfCanvasPreview
                  pdfBytes={previewPdfBytes}
                  pdfUrl={previewPdfUrl}
                  meta={previewPdfMeta}
                  title={`${handoutVersionLabel(version)} PDF`}
                  canvasLabel={`${handoutVersionLabel(version)} PDF 预览`}
                />
              ) : (
                <PreviewPlaceholder
                  title={taskCompleted ? "点击上方按钮加载真实 PDF" : "任务完成后可打开真实 PDF"}
                  detail={taskCompleted ? "支持多页翻看。" : "生成完成后可预览。"}
                  loading={action === "preview-pdf"}
                />
              )
            ) : null}

            {mode === "review" ? (
              latexPreviewReady ? (
                <HandoutStructuredPreview latex={previewLatex} version={version} />
              ) : (
                <PreviewPlaceholder title="点击上方按钮加载结构审查" detail="用于核对章节、题目、公式和答案隔离。" loading={action === "preview"} />
              )
            ) : null}
          </div>

          <HandoutFeedbackReview
            task={task}
            version={version}
            pdfPreviewReady={pdfPreviewReady}
            latexPreviewReady={latexPreviewReady}
            feedbackRating={feedbackRating}
            feedbackDecision={feedbackDecision}
            feedbackComment={feedbackComment}
            submittingFeedback={submittingFeedback}
            feedbackMessage={feedbackMessage}
            feedbackHistory={feedbackHistory}
            loadingFeedbackHistory={loadingFeedbackHistory}
            onFeedbackRatingChange={onFeedbackRatingChange}
            onFeedbackDecisionChange={onFeedbackDecisionChange}
            onFeedbackCommentChange={onFeedbackCommentChange}
            onSubmitFeedback={onSubmitFeedback}
          />
        </>
      ) : (
        <PreviewPlaceholder title="先在左侧创建或选择一份讲义" detail="这里会显示摘要、PDF 和审查视图。" />
      )}
    </section>
  );
}

function HandoutFeedbackReview({
  task,
  version,
  pdfPreviewReady,
  latexPreviewReady,
  feedbackRating,
  feedbackDecision,
  feedbackComment,
  submittingFeedback,
  feedbackMessage,
  feedbackHistory,
  loadingFeedbackHistory,
  onFeedbackRatingChange,
  onFeedbackDecisionChange,
  onFeedbackCommentChange,
  onSubmitFeedback,
}: {
  task: TeachingTaskResponse;
  version: HandoutVersion;
  pdfPreviewReady: boolean;
  latexPreviewReady: boolean;
  feedbackRating: number;
  feedbackDecision: string;
  feedbackComment: string;
  submittingFeedback: boolean;
  feedbackMessage: string;
  feedbackHistory: TeachingHumanFeedbackResponse[];
  loadingFeedbackHistory: boolean;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const teacherVersion = version === "teacher";
  const lectureVersion = version === "lecture";
  const selectedDraft = handoutDraftForVersion(task, version);
  const hasAnswers = /答案|解析|评分点|teacherExplanation|参考/.test(selectedDraft ?? "");
  const studentLeakWarning = version === "student" && hasAnswers;
  const reviewHint = lectureVersion
    ? "核对横版讲解卡是否适合投屏，空白部分保持干净。"
    : teacherVersion
      ? "核对教师版答案、来源和讲评主线。"
      : "核对学生版是否只保留题目、提示和作答空间。";

  return (
    <section className="handout-review-checkpoint" aria-label="讲义人工审查">
      <div className="feedback-head">
        <div>
          <strong>人工审查</strong>
          <span>{reviewHint}</span>
        </div>
        {feedbackMessage ? <span>{feedbackMessage}</span> : null}
      </div>

      <div className="feedback-quality-list">
        <ReviewQualityItem tone={pdfPreviewReady ? "good" : "warning"} label="真实 PDF" value={pdfPreviewReady ? "已加载" : "未预览"} />
        <ReviewQualityItem tone={latexPreviewReady ? "good" : "warning"} label="结构审查" value={latexPreviewReady ? "已加载" : "未打开"} />
        <ReviewQualityItem tone={studentLeakWarning ? "warning" : "good"} label="版本隔离" value={studentLeakWarning ? "学生版疑似含答案" : "当前无明显泄露"} />
      </div>

      <form className="human-feedback-panel compact" onSubmit={onSubmitFeedback}>
        <div className="feedback-grid">
          <label>
            <span>评分</span>
            <input className="form-input" type="number" min={1} max={5} value={feedbackRating} onChange={(event) => onFeedbackRatingChange(Number(event.target.value))} />
          </label>
          <label>
            <span>结论</span>
            <select className="form-select" value={feedbackDecision} onChange={(event) => onFeedbackDecisionChange(event.target.value)}>
              <option value="helpful">可直接使用</option>
              <option value="confusing">需要讲清楚</option>
              <option value="needs_revision">需要修改</option>
            </select>
          </label>
        </div>
        <label>
          <span>审查意见</span>
          <textarea
            className="form-textarea"
            value={feedbackComment}
            onChange={(event) => onFeedbackCommentChange(event.target.value)}
            placeholder="例如：教师版补来源页码；学生版删除答案；第 3 题增加作答空间。"
          />
        </label>
        <button type="submit" className="btn btn-secondary btn-sm" disabled={submittingFeedback}>
          {submittingFeedback ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
          <span>提交审查</span>
        </button>
      </form>

      <div className="feedback-history-panel compact">
        <div className="feedback-history-head">
          <div>
            <strong>审查记录</strong>
            <span>{loadingFeedbackHistory ? "同步中" : `${feedbackHistory.length} 条`}</span>
          </div>
        </div>
        {!feedbackHistory.length ? (
          <div className="empty-state compact">当前讲义还没有人工审查记录。</div>
        ) : (
          <div className="feedback-history-list">
            {feedbackHistory.slice(0, 4).map((item) => (
              <article className="feedback-history-item" key={item.feedbackId}>
                <div className="feedback-history-title">
                  <strong>{decisionLabel(item.decision)}</strong>
                  <span>{item.rating} 星 · {formatReviewTime(item.createdAt)}</span>
                </div>
                {item.comment ? <p>{item.comment}</p> : null}
              </article>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function handoutDraftForVersion(task: TeachingTaskResponse, version: HandoutVersion) {
  if (version === "lecture") {
    return task.lectureHandoutLatex ?? task.teacherHandoutLatex ?? task.handoutLatex ?? "";
  }
  if (version === "student") {
    return task.studentHandoutLatex ?? task.handoutLatex ?? "";
  }
  return task.teacherHandoutLatex ?? task.handoutLatex ?? "";
}

function handoutVersionLabel(version: HandoutVersion) {
  if (version === "lecture") return "横版讲解稿";
  return version === "teacher" ? "教师版讲义" : "学生版讲义";
}

function ReviewQualityItem({ tone, label, value }: { tone: "good" | "warning"; label: string; value: string }) {
  return (
    <div className={`feedback-quality-item ${tone}`}>
      <ShieldCheck size={14} />
      <strong>{label}</strong>
      <em>{value}</em>
    </div>
  );
}

function PreviewPlaceholder({ title, detail, loading = false }: { title: string; detail: string; loading?: boolean }) {
  return (
    <div className="handout-workspace-placeholder">
      {loading ? <Loader2 className="spin" size={18} /> : <FileText size={18} />}
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

function statusLabel(status?: string) {
  const normalized = (status ?? "").toUpperCase();
  if (normalized === "COMPLETED") return "已完成";
  if (normalized === "FAILED") return "失败";
  if (normalized === "RUNNING") return "生成中";
  if (normalized === "CREATED") return "已创建";
  return "等待中";
}

function providerLabel(provider?: string) {
  const normalized = (provider ?? "").toLowerCase();
  const labels: Record<string, string> = {
    openai: "OpenAI",
    deepseek: "DeepSeek",
    dashscope: "通义千问",
    ark: "火山方舟",
    local: "本地模型",
  };
  return labels[normalized] ?? (provider || "模型");
}

function actionText(action: string) {
  const labels: Record<string, string> = {
    "preview-pdf": "正在渲染真实 PDF 预览...",
    preview: "正在生成结构审查视图...",
    pdf: "正在导出 PDF...",
    latex: "正在导出 TeX...",
    zip: "正在打包 ZIP...",
  };
  return labels[action] ?? "正在处理...";
}

function decisionLabel(decision: string) {
  const labels: Record<string, string> = {
    helpful: "可直接使用",
    confusing: "需要讲清楚",
    needs_revision: "需要修改",
  };
  return labels[decision] ?? decision;
}

function formatReviewTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
