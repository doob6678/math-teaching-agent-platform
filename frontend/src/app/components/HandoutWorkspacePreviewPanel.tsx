import { FormEvent, useEffect, useMemo, useState } from "react";
import { Download, Eye, FileText, Loader2, RefreshCw, ShieldCheck } from "lucide-react";
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
  previewError,
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
  onResumeTask,
  onExportPdf,
  onSaveHandoutVersion,
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
  previewError: string;
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
  onResumeTask: () => void;
  onExportPdf: () => void;
  onSaveHandoutVersion: (latex: string) => void;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const [mode, setMode] = useState<PreviewMode>("summary");
  const [editingVersion, setEditingVersion] = useState(false);
  const [editedLatex, setEditedLatex] = useState("");
  const selectedDraft = task ? handoutDraftForVersion(task, version) : "";
  const taskKey = task ? `${task.taskId}:${version}` : "";
  const pdfPreviewReady = Boolean(task && previewPdfBytes && previewPdfUrl && previewPdfMeta && previewPdfTaskKey === taskKey);
  const latexPreviewReady = Boolean(task && previewLatex.trim() && previewTaskKey === taskKey);
  const taskCompleted = task?.status === "COMPLETED";
  const taskFailed = task?.status === "FAILED";
  const previewRateLimited = /429|频繁|限流|过多/.test(previewError);
  const studentLeakWarning = version === "student" && /答案|解析|评分点|teacherExplanation|参考答案/.test(selectedDraft ?? "");
  const statusText = useMemo(() => statusLabel(task?.status), [task?.status]);
  const reviewSummary = useMemo(() => buildReviewSummary(task), [task]);
  const workspaceSummary = useMemo(
    () => buildWorkspaceSummary(task, version, pdfPreviewReady, latexPreviewReady),
    [task, version, pdfPreviewReady, latexPreviewReady],
  );

  useEffect(() => {
    setMode("summary");
    setEditingVersion(false);
    setEditedLatex(selectedDraft);
  }, [task?.taskId, version, selectedDraft]);

  useEffect(() => {
    if (taskCompleted && pdfPreviewReady) {
      setMode("pdf");
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
          <span>当前讲义</span>
          <strong>{task ? task.learningGoal || task.questionText || "当前讲义" : "等待选择讲义"}</strong>
        </div>
        <div className="handout-workspace-version-switch" role="tablist" aria-label="讲义版本">
          <button type="button" className={`handout-preview-mode${version === "teacher" ? " active" : ""}`} onClick={() => onVersionChange("teacher")} disabled={!task?.teacherHandoutLatex?.trim() && !task?.handoutLatex?.trim()}>
            教师版
          </button>
          <button type="button" className={`handout-preview-mode${version === "student" ? " active" : ""}`} onClick={() => onVersionChange("student")} disabled={!task?.studentHandoutLatex?.trim()}>
            学生版
          </button>
          {task ? (
            <button type="button" className={`handout-preview-mode${version === "lecture" ? " active" : ""}`} onClick={() => onVersionChange("lecture")} disabled={!task.lectureHandoutLatex?.trim()}>
              16:10
            </button>
          ) : null}
        </div>
      </div>

      {task ? (
        <>
          <div className="handout-workspace-metrics compact">
            <div><span>状态</span><strong>{statusText}</strong></div>
            <div><span>来源</span><strong>{task.evidence.length} 条</strong></div>
            <div><span>预览</span><strong>{pdfPreviewReady ? "PDF 已就绪" : latexPreviewReady ? "结构已就绪" : "待加载"}</strong></div>
          </div>

          <div className="handout-workspace-actions">
            <div className="handout-preview-mode-switch" role="tablist" aria-label="讲义预览模式">
              <button type="button" className={`handout-preview-mode${mode === "summary" ? " active" : ""}`} onClick={() => setMode("summary")}>概览</button>
              <button type="button" className={`handout-preview-mode${mode === "pdf" ? " active" : ""}`} onClick={openPdfMode}>PDF</button>
              <button type="button" className={`handout-preview-mode${mode === "review" ? " active" : ""}`} onClick={openReviewMode}>校对</button>
            </div>
            <div className="handout-workspace-button-row">
              <button className="handout-action-btn" type="button" onClick={openPdfMode}>
                {action === "preview-pdf" ? <Loader2 className="spin" size={15} /> : <Eye size={15} />}
                <span>预览 PDF</span>
              </button>
              <button className="handout-action-btn" type="button" onClick={openReviewMode}>
                {action === "preview" ? <Loader2 className="spin" size={15} /> : <FileText size={15} />}
                <span>结构校对</span>
              </button>
              <button
                className="handout-action-btn"
                type="button"
                onClick={() => { setEditingVersion(true); setMode("review"); }}
                disabled={!taskCompleted || !selectedDraft || action === "save-version"}
              >
                {action === "save-version" ? <Loader2 className="spin" size={15} /> : <FileText size={15} />}
                <span>编辑本版</span>
              </button>
              <button className="handout-action-btn primary" type="button" onClick={onExportPdf} disabled={!taskCompleted || !selectedDraft}>
                {action === "pdf" ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
                <span>下载 PDF</span>
              </button>
            </div>
          </div>

          {action ? <div className="handout-workspace-status" role="status" aria-live="polite">{actionText(action)}</div> : null}
          {exportMessage ? <div className="handout-workspace-status">{exportMessage}</div> : null}

          {taskFailed ? (
            <section className="handout-review-banner warning handout-task-failure" role="alert">
              <div>
                <strong>生成失败，可从已记录进度继续</strong>
                <p>{task.errorMessage || "本次生成在一个流程节点失败，已有节点、资料和耗时已保留。"}</p>
                <span className="handout-task-failure-progress">
                  已完成 {task.nodes.filter((node) => node.status === "completed").length} 个节点，命中 {task.evidence.length} 条资料，已记录 {task.stageTimings?.length ?? 0} 个阶段耗时
                </span>
              </div>
              <button className="handout-action-btn primary" type="button" onClick={onResumeTask} disabled={action === "resume"}>
                {action === "resume" ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                <span>继续生成</span>
              </button>
            </section>
          ) : null}

          <div className="handout-workspace-body">
            {editingVersion ? (
              <section className="handout-review-checkpoint" aria-label="编辑当前讲义版本">
                <div className="feedback-head">
                  <div>
                    <strong>编辑{handoutVersionLabel(version)}</strong>
                    <span>保存后只更新当前任务的这个版本，并重新执行版本安全检查。</span>
                  </div>
                </div>
                <textarea
                  className="form-textarea"
                  value={editedLatex}
                  onChange={(event) => setEditedLatex(event.target.value)}
                  rows={18}
                  aria-label="讲义 LaTeX 编辑器"
                />
                <div className="handout-workspace-button-row">
                  <button
                    className="handout-action-btn primary"
                    type="button"
                    disabled={!editedLatex.trim() || action === "save-version"}
                    onClick={() => onSaveHandoutVersion(editedLatex)}
                  >
                    {action === "save-version" ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
                    <span>保存本版</span>
                  </button>
                  <button className="handout-action-btn" type="button" onClick={() => setEditingVersion(false)}>
                    取消编辑
                  </button>
                </div>
              </section>
            ) : null}
            {mode === "summary" ? (
              <div className="handout-workspace-summary">
                <div className="handout-workspace-summary-grid">
                  {workspaceSummary.map((item) => (
                    <article className={`handout-summary-card ${item.tone}`} key={item.label}>
                      <span>{item.label}</span>
                      <strong>{item.value}</strong>
                      <p>{item.detail}</p>
                    </article>
                  ))}
                </div>
                {reviewSummary ? (
                  <section className={`handout-review-banner ${reviewSummary.tone}`}>
                    <div>
                      <strong>{reviewSummary.title}</strong>
                      <p>{reviewSummary.detail}</p>
                    </div>
                    <span>{reviewSummary.badge}</span>
                  </section>
                ) : null}
                {studentLeakWarning ? (
                  <section className="handout-review-banner warning">
                    <div>
                      <strong>学生版疑似含答案</strong>
                      <p>当前版本可能混入了解析或答案，建议切到结构校对后处理。</p>
                    </div>
                    <span>需处理</span>
                  </section>
                ) : null}
                <LectureHandoutPreview task={task} version={version} />
              </div>
            ) : null}

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
                <>
                  <PreviewPlaceholder
                    title={taskCompleted ? "点击上方按钮加载真实 PDF" : taskFailed ? "生成失败，先继续任务或重试 PDF 加载" : "任务完成后可打开 PDF"}
                    detail={taskCompleted ? "支持多页翻看。" : taskFailed ? "不会重新生成讲义，只重新申请当前版本的预览能力。" : "生成完成后可预览。"}
                    loading={action === "preview-pdf"}
                  />
                  {previewError ? (
                    <section className="handout-review-banner warning handout-preview-error" role="alert">
                      <div>
                        <strong>{previewRateLimited ? "PDF 预览被限流" : "PDF 预览失败"}</strong>
                        <p>{previewError}</p>
                      </div>
                      <button className="handout-action-btn" type="button" onClick={onPreviewPdf} disabled={action === "preview-pdf"}>
                        <RefreshCw size={15} />
                        <span>继续加载 PDF</span>
                      </button>
                    </section>
                  ) : null}
                </>
              )
            ) : null}

            {mode === "review" ? (
              <>
                {latexPreviewReady ? (
                  <HandoutStructuredPreview latex={previewLatex} version={version} />
                ) : (
                  <PreviewPlaceholder
                    title="点击上方按钮加载结构校对"
                    detail="这里只显示当前版本的结构与反馈。"
                    loading={action === "preview"}
                  />
                )}
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
            ) : null}
          </div>
        </>
      ) : (
        <PreviewPlaceholder title="从历史或当前流程里选一份讲义" detail="这里会显示当前版本、PDF 和校对结果。" />
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
  const studentLeakWarning = version === "student" && /答案|解析|评分点|teacherExplanation|参考答案/.test(selectedDraft ?? "");
  const reviewSummary = buildReviewSummary(task);
  const reviewHint = lectureVersion
    ? "确认投屏内容和节奏。"
    : teacherVersion
      ? "确认答案、来源和讲评主线。"
      : "确认只保留题目、提示和作答空间。";

  return (
    <section className="handout-review-checkpoint" aria-label="讲义校对反馈">
      <div className="feedback-head">
        <div>
          <strong>校对反馈</strong>
          <span>{reviewHint}</span>
        </div>
        {feedbackMessage ? <span>{feedbackMessage}</span> : null}
      </div>

      <div className="feedback-quality-list">
        <ReviewQualityItem tone={pdfPreviewReady ? "good" : "warning"} label="真实 PDF" value={pdfPreviewReady ? "已加载" : "未预览"} />
        <ReviewQualityItem tone={latexPreviewReady ? "good" : "warning"} label="结构校对" value={latexPreviewReady ? "已加载" : "未打开"} />
        <ReviewQualityItem tone={studentLeakWarning ? "warning" : "good"} label="版本隔离" value={studentLeakWarning ? "学生版疑似含答案" : "当前无明显泄漏"} />
        {reviewSummary ? <ReviewQualityItem tone={reviewSummary.tone} label="后端校对" value={reviewSummary.badge} /> : null}
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
          <span>反馈</span>
          <textarea
            className="form-textarea"
            value={feedbackComment}
            onChange={(event) => onFeedbackCommentChange(event.target.value)}
            placeholder="例如：教师版补页码；学生版删答案；第 3 题留白再多一点。"
          />
        </label>
        <button type="submit" className="handout-action-btn primary" disabled={submittingFeedback}>
          {submittingFeedback ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
          <span>保存反馈</span>
        </button>
      </form>

      <div className="feedback-history-panel compact">
        <div className="feedback-history-head">
          <div>
            <strong>反馈记录</strong>
            <span>{loadingFeedbackHistory ? "同步中" : `${feedbackHistory.length} 条`}</span>
          </div>
        </div>
        {!feedbackHistory.length ? (
          <div className="empty-state compact">当前还没有反馈记录。</div>
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
    return task.lectureHandoutLatex ?? "";
  }
  if (version === "student") {
    return task.studentHandoutLatex ?? "";
  }
  return task.teacherHandoutLatex ?? task.handoutLatex ?? "";
}

function handoutVersionLabel(version: HandoutVersion) {
  if (version === "lecture") return "16:10 讲解版";
  return version === "teacher" ? "教师版讲义" : "学生版讲义";
}

function buildWorkspaceSummary(
  task: TeachingTaskResponse | null,
  version: HandoutVersion,
  pdfPreviewReady: boolean,
  latexPreviewReady: boolean,
) {
  if (!task) return [];
  const draftSections = task.draftSections;
  const reviewSummary = buildReviewSummary(task);
  const lectureCardCount = draftSections?.lectureCards?.filter((item) => item.trim().length > 0).length ?? 0;
  const exerciseCount = draftSections?.exercises?.filter((item) => item.trim().length > 0).length ?? 0;
  const sourceRefCount = draftSections?.sourceRefs?.filter((item) => item.trim().length > 0).length ?? 0;
  const versionTitle = version === "teacher" ? "教师版" : version === "student" ? "学生版" : "16:10 讲解版";
  return [
    {
      label: "当前版本",
      value: versionTitle,
      detail: version === "student" ? "学生留白优先。" : version === "lecture" ? "适合投屏讲解。" : "答案与讲评保留在教师版。",
      tone: "neutral" as const,
    },
    {
      label: "结构内容",
      value: `${lectureCardCount + exerciseCount + sourceRefCount} 项`,
      detail: `讲解卡 ${lectureCardCount} · 练习 ${exerciseCount} · 来源 ${sourceRefCount}`,
      tone: lectureCardCount + exerciseCount + sourceRefCount > 0 ? "good" as const : "warning" as const,
    },
    {
      label: "预览状态",
      value: pdfPreviewReady ? "PDF 已就绪" : latexPreviewReady ? "结构已就绪" : "待加载",
      detail: pdfPreviewReady ? "已经拿到真实 PDF。" : "可先看结构或继续生成。",
      tone: pdfPreviewReady || latexPreviewReady ? "good" as const : "neutral" as const,
    },
    {
      label: "校对结论",
      value: reviewSummary?.badge ?? "未返回",
      detail: reviewSummary?.detail ?? "当前任务还没有结构化校对摘要。",
      tone: reviewSummary?.tone ?? "neutral" as const,
    },
  ];
}

function buildReviewSummary(task: TeachingTaskResponse | null) {
  if (!task?.draftReview) return null;
  const review = task.draftReview;
  const warningCount = review.findings.filter((item) => item.severity !== "info").length;
  if (review.status === "READY") {
    return {
      title: "结构化校对已通过",
      detail: warningCount > 0 ? `仍有 ${warningCount} 条提醒，可顺手核对。` : "当前版本可以继续预览或下载。",
      badge: warningCount > 0 ? `${warningCount} 条提醒` : "已通过",
      tone: "good" as const,
    };
  }
  return {
    title: "当前仍有待核对项",
    detail: `共有 ${review.findings.length} 条发现、${review.patches.length} 条修订建议，建议先看结构校对。`,
    badge: `${review.findings.length} 项待处理`,
    tone: "warning" as const,
  };
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

function actionText(action: string) {
  const labels: Record<string, string> = {
    "preview-pdf": "正在渲染真实 PDF 预览...",
    "save-version": "正在保存并检查当前版本...",
    preview: "正在生成结构校对视图...",
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
